package com.wangzan101.mika.wechat

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.wechat.WechatOwnerPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Sends only to the already bound owner; no contact or destination is supplied by the model. */
class AndroidWechatOwnerPort(private val app: MikaApp, private val runKey: String) : WechatOwnerPort {
    private val expectedAccount = app.wechat.store.account()
    private val expectedPeer = app.wechat.store.boundOwnerPeer()

    override suspend fun send(text: String): JsonObject {
        val runtime = app.wechat
        val peer = expectedPeer?.takeIf { expectedAccount != null && runtime.store.account() == expectedAccount && runtime.store.boundOwnerPeer() == it }
            ?: return result("unavailable",
            "WeChat is not enabled, bound, or has no owner conversation token. Send a message to Mika in WeChat and try again.")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((peer.threadId + "\u0000" + text).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val delivery = Delivery.Notice("owner-send:$runKey:$digest", peer.threadId, text)
        val state = app.outbox.deliver(runtime.channel, peer, delivery).state
        return when (state) {
            DeliveryState.Delivered -> result("delivered", "WeChat accepted the message; this does not confirm it was read.")
            DeliveryState.Uncertain, DeliveryState.Attempting -> result("uncertain", "Delivery could not be confirmed. Do not retry automatically.")
            else -> result("failed", "WeChat did not confirm delivery. Check the channel and report failure.")
        }
    }

    private fun result(status: String, message: String) = buildJsonObject {
        put("ok", status == "delivered"); put("status", status); put("message", message)
    }
}
