package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
