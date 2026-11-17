package com.wangzan101.mika.core.wechat

import com.wangzan101.mika.core.capability.*
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.prompt.Prompts
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
    val toOwner = Regex("(发送|发|推送|通知|同步|回传)[^，。；！？\\n]{0,18}(我的微信|我微信|本人微信)").containsMatchIn(g)
    val viaWechat = Regex("微信[^，。；！？\\n]{0,12}(发给我|发送给我|推送给我|发我|通知我|给我发|回传给我)").containsMatchIn(g)
    // A generic WeChat delivery refers to the bound owner only when no other recipient is named.
    // Ambiguous named-recipient requests must never be redirected to the owner.
    val otherRecipient = Regex("(给|发给|发送给|通知|转给)(?!我|本人|自己|我的微信|微信)[^，。；！？\\n]{1,12}")
        .containsMatchIn(g)
    val genericWechat = Regex("(发送|发|推送|通知|同步|回传)[^，。；！？\\n]{0,8}(到|至|给)微信(?:上|里)?(?=$|[，。；！？\\s])")
        .containsMatchIn(g)
    return (toOwner || viaWechat || genericWechat) && !otherRecipient
}

/** Task results from an inbound channel already have their own reply path. */
fun shouldSendOwnerResult(origin: com.wangzan101.mika.core.run.RunOrigin, goal: String,
    conditionCheck: Boolean, sceneEnabled: Boolean): Boolean = !conditionCheck && when (origin) {
    com.wangzan101.mika.core.run.RunOrigin.Ui -> explicitWechatOwnerRequest(goal)
    com.wangzan101.mika.core.run.RunOrigin.Trigger -> sceneEnabled
    else -> false
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
        Prompts.tool("wechat_send_owner"),
        mapOf("text" to str("Complete message for the bound owner, including relevant uncertainties. No secrets or invented facts.")),
        listOf("text"))
    override fun available(ctx: ToolContext) = ctx[WechatOwnerCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val text = a.str("text", min = 1, max = 4000)!!
        return ToolExecution(ctx[WechatOwnerCapability.KEY]!!.port.send(text))
    }
}
