package com.wangzan101.mika.wechat

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.wechat.WechatOwnerPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

class AndroidWechatOwnerPort(app: MikaApp, runKey: String) : WechatOwnerPort {
    private val sender = WechatOwnerSender(app.wechat.store, app.wechat.channel, app.outbox, runKey)
    override suspend fun send(text: String): JsonObject = sender.send(text)
}

/** Sends only to the already bound owner; no contact or destination is supplied by the model. */
internal class WechatOwnerSender(
    private val store: WechatStore, private val channel: Channel, private val outbox: Outbox, private val runKey: String,
) : WechatOwnerPort {
    private val expectedAccount = store.account()
    private val expectedPeer = store.boundOwnerPeer()

    override suspend fun send(text: String): JsonObject {
        val peer = expectedPeer?.takeIf { expectedAccount != null && store.account() == expectedAccount && store.boundOwnerPeer() == it }
            ?: return result("unavailable",
                "WeChat is not enabled, bound, or has no owner conversation token. Send a message to Mika in WeChat and try again.")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((peer.threadId + "\u0000" + text).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val delivery = Delivery.Notice("owner-send:$runKey:$digest", peer.threadId, text)
        val state = outbox.deliver(channel, peer, delivery).state
        val diagnostic = (channel as? WechatChannel)?.diagnosticFor(delivery.id)
        return when (state) {
            DeliveryState.Delivered -> result("delivered", "WeChat accepted the message; this does not confirm it was read.")
            DeliveryState.Uncertain, DeliveryState.Attempting -> result("uncertain", "Delivery could not be confirmed. Do not retry automatically. Report the diagnostic code if present.", diagnostic)
            else -> result("failed", "WeChat did not confirm delivery. Report the diagnostic code if present.", diagnostic)
        }
    }

    private fun result(status: String, message: String, diagnostic: String? = null) = buildJsonObject {
        put("ok", status == "delivered"); put("status", status); put("message", message)
        if (diagnostic != null) put("diagnostic", diagnostic)
    }
}
