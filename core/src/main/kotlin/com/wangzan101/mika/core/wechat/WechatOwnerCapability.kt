package com.wangzan101.mika.core.wechat

import com.wangzan101.mika.core.capability.*
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.JsonObject

/** Host accepts no recipient from the model: delivery goes only to the currently bound owner. */
interface WechatOwnerPort {
    suspend fun send(text: String): JsonObject
}

/** Require an explicit, affirmative owner-directed WeChat send in the original UI task. */
fun explicitWechatOwnerRequest(goal: String): Boolean {
    val g = goal.lowercase()
    if ("微信" !in g) return false
    if (Regex("(不要|别|无需|不需要|禁止|不必|不用).{0,12}(发|推送|发送|通知|同步|回传).{0,12}微信").containsMatchIn(g)) return false
    val owner = Regex("我的微信|我微信|微信.{0,12}(给我|通知我|发我|给本人)|微信.{0,6}本人").containsMatchIn(g)
    val send = Regex("发|发送|推送|通知|同步|回传").containsMatchIn(g)
    return owner && send
}

internal class WechatOwnerCapability(val port: WechatOwnerPort) : Capability {
    override val id = "wechat_owner"
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = wechatOwnerTools
    override fun sections(env: PromptEnv): List<String> = listOf(
        "The user explicitly requested sending this task's result to their own bound WeChat private chat. " +
        "After completing the requested work, use wechat_send_owner once with the accurate result and its uncertainties, only when the user asked to send it. Do not send progress updates or unrelated screen content. " +
        "Do not use the WeChat app, browser, or another recipient as a substitute. " +
        "A delivered status means the WeChat API accepted the message, not that the user read it. " +
        "If failed or uncertain, report that accurately and never resend an uncertain result automatically."
    )
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    companion object { val KEY = CapabilityKey<WechatOwnerCapability>("wechat_owner") }
}

internal val wechatOwnerTools: List<AgentTool> = listOf(WechatOwnerTool())

private class WechatOwnerTool : AgentTool {
    override val capability = "wechat_owner"
    override val verb = Verb.WechatSendOwner
    override val offScreen = true
    override val risk = Risk.Write
    override val logged = true
    override val label = "发送到我的微信"
    override val def = ToolDef("wechat_send_owner",
        "Send text to the user's own already-bound WeChat private channel only. Use only when explicitly requested. Report delivery state accurately.",
        mapOf("text" to str("Complete message for the bound owner, including relevant uncertainties. No secrets or invented facts.")),
        listOf("text"))
    override fun available(ctx: ToolContext) = ctx[WechatOwnerCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val text = a.str("text", min = 1, max = 4000)!!
        return ToolExecution(ctx[WechatOwnerCapability.KEY]!!.port.send(text))
    }
}
