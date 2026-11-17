package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

class WechatOwnerSenderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    @Test fun sendsOnlyToBoundOwnerAndDoesNotRepeatUncertainDelivery() = runBlocking {
        val store = WechatStore(tmp.newFolder(), cipher)
        val account = WechatAccount("token", "https://example.test", "bot", "owner")
        store.saveAccount(account)
        store.setEnabled(true)
        val peer = store.messagePeer(account, "context")!!
        val sent = mutableListOf<Pair<ChannelPeer, Delivery>>()
        val channel = object : Channel {
            override val id = WechatChannel.ID
            override val confirms = false
            override fun isAlive(peer: ChannelPeer) = store.accepts(peer)
            override fun start(sink: ChannelInputSink) = AutoCloseable { }
            override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
                sent += peer to delivery
                return SendOutcome.Uncertain
            }
        }
        val sender = WechatOwnerSender(store, channel, Outbox(), "run-1")
        assertEquals("uncertain", sender.send("车辆状态").getValue("status").jsonPrimitive.content)
        assertEquals("uncertain", sender.send("车辆状态").getValue("status").jsonPrimitive.content)
        assertEquals(1, sent.size)
        assertEquals(peer, sent.single().first)
        assertEquals("车辆状态", (sent.single().second as Delivery.Notice).text)

        store.saveAccount(WechatAccount("new-token", "https://example.test", "other-bot", "other-owner"))
        store.setEnabled(true)
        assertEquals("unavailable", sender.send("新的结果").getValue("status").jsonPrimitive.content)
        assertEquals(1, sent.size)
    }

    @Test fun acceptedMessageReportsDelivered() = runBlocking {
        val store = WechatStore(tmp.newFolder(), cipher)
        val account = WechatAccount("token", "https://example.test", "bot", "owner")
        store.saveAccount(account)
        store.setEnabled(true)
        val owner = store.messagePeer(account, "context")!!
        val channel = object : Channel {
            override val id = WechatChannel.ID
            override val confirms = false
            override fun isAlive(peer: ChannelPeer) = store.accepts(peer)
            override fun start(sink: ChannelInputSink) = AutoCloseable { }
            override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
                assertEquals(owner, peer)
                return SendOutcome.Delivered
            }
        }
        val result = WechatOwnerSender(store, channel, Outbox(), "run-3").send("已核实的结果")
        assertEquals("delivered", result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("ok").jsonPrimitive.content == "true")
    }

    @Test fun missingOwnerContextDoesNotAttemptDelivery() = runBlocking {
        val store = WechatStore(tmp.newFolder(), cipher)
        store.saveAccount(WechatAccount("token", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        val channel = object : Channel {
            override val id = WechatChannel.ID
            override val confirms = false
            override fun start(sink: ChannelInputSink) = AutoCloseable { }
            override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome = error("must not send")
        }
        assertEquals("unavailable", WechatOwnerSender(store, channel, Outbox(), "run-2")
            .send("车辆状态").getValue("status").jsonPrimitive.content)
    }

    @Test fun uncertainHttpFailureSurfacesSafeDiagnosticWithoutRetrying() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            val account = WechatAccount("secret-token", server.url("/").toString(), "bot", "owner")
            store.saveAccount(account)
            store.setEnabled(true)
            store.messagePeer(account, "secret-context")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "test"), log = {})
            val sender = WechatOwnerSender(store, channel, Outbox(), "run-4")
            server.enqueue(MockResponse().setResponseCode(500).setBody("secret server response"))
            val first = sender.send("private message")
            assertEquals("uncertain", first.getValue("status").jsonPrimitive.content)
            assertEquals("wechat_http_500", first.getValue("diagnostic").jsonPrimitive.content)
            assertFalse(first.toString().contains("secret"))
            assertEquals("uncertain", sender.send("private message").getValue("status").jsonPrimitive.content)
            assertEquals(1, server.requestCount)
        }
    }
}
