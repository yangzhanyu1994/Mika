package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 第一次问模型之前，能对上公开系统设置动作时先跳一次。
 * 只使用 [LinkPolicy] 里已有的 android.settings 动作，不记坐标、不记输入。
 * 跳完必须重新读屏；对不上或打不开就停在这一次，交给模型和已有的路线回放。
 */
internal object DirectEntry {
    data class Jump(val action: String)

    data class Attempt(
        val action: String,
        /** 端口接受了这次打开。 */
        val opened: Boolean,
        /** 打开之后前台是系统设置。 */
        val verified: Boolean,
        val openCalls: Int,
        val freshReads: Int,
        val snapshotId: String?,
        val foreground: String?,
        val error: String?,
    )

    private data class Page(val action: String, val phrases: List<String>)

    private val pages = listOf(
        Page("android.settings.WIFI_SETTINGS", listOf("wlan", "wi-fi", "wifi", "无线局域网", "无线网络")),
        Page("android.settings.BLUETOOTH_SETTINGS", listOf("蓝牙", "bluetooth")),
        Page("android.settings.DISPLAY_SETTINGS", listOf("显示设置", "display settings")),
        Page("android.settings.SOUND_SETTINGS", listOf("声音设置", "sound settings")),
        Page("android.settings.BATTERY_SAVER_SETTINGS", listOf("省电", "battery saver")),
        Page("android.settings.LOCATION_SOURCE_SETTINGS", listOf("定位服务", "location settings", "位置信息")),
        Page("android.settings.APPLICATION_SETTINGS", listOf("应用设置", "应用管理", "apps settings")),
        Page("android.settings.SETTINGS", listOf("系统设置", "手机设置", "打开设置", "settings")),
    )

    /** 这些话里的动作仍走原来的确认，不抢先跳页。 */
    private val sensitive = Regex("发送|付款|支付|购买|下单|删除|卸载|密码|验证码|otp|password|purchase|delete|send money", RegexOption.IGNORE_CASE)
    private val compound = Regex("然后|并且|接着|之后再|and then|after that")
    /** 没有核对过的第三方入口不猜。 */
    private val foreignApp = Regex("微信|支付宝|淘宝|抖音|wechat|alipay")

    fun match(goal: String): Jump? {
        val raw = goal.trim()
        if (raw.length !in 2..80) return null
        if (sensitive.containsMatchIn(raw) || compound.containsMatchIn(raw) || foreignApp.containsMatchIn(raw)) return null
        val text = raw.lowercase()
        val hits = pages.filter { page -> page.phrases.any { phrase -> text.contains(phrase) } }
        if (hits.size != 1) return null
        // 「设置」是更宽的一页。话里已经点名了另一页时不会落到这里（hits 只有一个）。
        return Jump(hits.single().action)
    }

    fun settingsForeground(packageName: String?): Boolean {
        val name = packageName?.lowercase() ?: return false
        return name == "com.android.settings" || name.endsWith(".settings") || ".settings." in name
    }

    suspend fun attempt(tools: ToolDispatcher, jump: Jump, run: suspend (Part.ToolCall) -> ToolExecution): Attempt {
        val args = openArgs(jump)
        val opened = run(Part.ToolCall("direct_entry", "phone_open_link", args.toString()))
        val error = (opened.content["error"] as? JsonPrimitive)?.contentOrNull
        val ok = opened.content["ok"] == JsonPrimitive(true)
        if (!ok) return Attempt(jump.action, false, false, 1, 0, null, null, error)
        val foreground = (opened.content["currentApp"] as? JsonPrimitive)?.contentOrNull
        val verified = settingsForeground(foreground)
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)
            ?: return Attempt(jump.action, true, verified, 1, 0, null, foreground, if (verified) null else "foreground_not_settings")
        val read = tools.executeRead(Part.ToolCall("direct_entry_read", dump, "{}"))
        val snapshotId = (read.content["snapshotId"] as? JsonPrimitive)?.contentOrNull
        return Attempt(
            jump.action, true, verified, 1, 1, snapshotId, foreground,
            if (verified) null else "foreground_not_settings",
        )
    }

    fun note(attempt: Attempt): String = when {
        attempt.verified ->
            "Opened ${attempt.action} with a public Android settings intent. The screen changed. Use only the fresh read from after this open; do not reuse an older snapshot or ref."
        attempt.opened ->
            "Tried ${attempt.action} once, but the foreground is ${attempt.foreground ?: "unknown"}, not Settings. Do not retry this intent. Read the current screen and continue from there."
        else ->
            "Tried ${attempt.action} once and it did not open (${attempt.error ?: "unavailable"}). Do not retry this intent. Continue by reading the current screen."
    }

    /** 打开调用本身不能带读屏编号。测试与运行都用这一份参数。 */
    fun openArgs(jump: Jump): JsonObject = buildJsonObject { put("action", jump.action) }
}
