package com.wangzan101.mika.calls

import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import kotlinx.serialization.json.*

/** One native runtime for the local agent, UI and authenticated MCP endpoint. */
class AndroidPhoneCallPort(private val runtime: CallRuntime) : PhoneCallPort {
    override suspend fun state(): JsonObject {
        runtime.refresh()
        return snapshot()
    }

    private fun snapshot() = buildJsonObject {
        val config = runtime.settings.state.value
        val state = runtime.state.value
        put("enabled", config.enabled)
        put("prepared", state.prepared)
        put("audio_ready", state.audioReady)
        put("audio_verified", config.audioVerified)
        put("helper_connected", state.shellConnected)
        put("notifications_available", state.notificationsAvailable)
        state.audioDetail?.let { put("audio_detail", it) }
        put("credentials_configured", config.credentialsConfigured)
        put("phase", state.phase.name.lowercase())
        put("ai_active", state.aiActive)
        put("auto_answer", config.autoAnswer)
        put("auto_answer_pending", state.pendingAutoAnswer)
        put("max_call_seconds", config.maxCallSeconds)
        state.direction?.let { put("direction", it.name.lowercase()) }
        state.callId?.let { put("call_id", it) }
        state.number?.let { put("number", it) }
        state.detail?.let { put("detail", it) }
        putJsonArray("sims") {
            state.simOptions.forEach { sim -> add(buildJsonObject {
                put("id", sim.id); put("label", sim.label); put("default", sim.isDefault)
            }) }
        }
    }

    private fun result(result: CallActionResult) = buildJsonObject {
        put("ok", result.ok); put("message", result.message); put("call", snapshot())
    }
    override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?) = result(runtime.dial(
        number, simId, task?.let { CallTaskBrief(it.goal, it.questions, it.context, it.constraints) },
    ))
    override suspend fun answer(callId: String) = result(runtime.answer(expectedCallId = callId))
    override suspend fun hangup(callId: String) = result(runtime.hangup(expectedCallId = callId))
    override suspend fun takeover() = result(runtime.takeover())

    override suspend fun saveIncomingTask(target: String, say: String, minutes: Int): JsonObject {
        if (target.isBlank() || target.length > 80 || say.isBlank() || say.length > 800 || minutes !in 1..1440) {
            return buildJsonObject { put("ok", false); put("message", "来电任务参数无效") }
        }
        return try {
            val now = System.currentTimeMillis()
            val (saved, created) = runtime.tasks.saveIncomingOnce(com.wangzan101.mika.calls.tasks.CallAssistantTask(
                id = "", kind = com.wangzan101.mika.calls.tasks.CallTaskTexts.KIND_INCOMING,
                title = target.trim().take(64), target = target.trim(), say = say.trim(),
                startMs = now, endMs = now + minutes * 60_000L,
                windowLabel = "创建后 $minutes 分钟内",
            ))
            buildJsonObject {
                put("ok", true); put("created", created); put("message", if (created) "已保存来电交代" else "已有相同交代，未重复创建")
                put("id", saved.id); put("target", saved.target); put("say", saved.say); saved.endMs?.let { put("expires_at_ms", it) }
                put("auto_answer", runtime.settings.state.value.autoAnswer)
            }
        } catch (_: Exception) {
            buildJsonObject { put("ok", false); put("message", "来电交代保存失败，请到通话中心检查") }
        }
    }

    override suspend fun manageIncomingTask(operation: String, id: String?, expectedSay: String?, say: String?): JsonObject = try {
        when (operation) {
            "list" -> buildJsonObject {
                put("ok", true)
                putJsonArray("tasks") {
                    runtime.tasks.snapshot().filter { it.kind == com.wangzan101.mika.calls.tasks.CallTaskTexts.KIND_INCOMING }.forEach { task ->
                        add(buildJsonObject {
                            put("id", task.id); put("target", task.target); put("say", task.say)
                            put("status", task.statusAt(System.currentTimeMillis()).name.lowercase())
                            task.endMs?.let { put("expires_at_ms", it) }
                            if (task.windowLabel.isNotBlank()) put("window", task.windowLabel)
                        })
                    }
                }
            }
            "edit", "delete" -> {
                val changed = id != null && expectedSay != null && (operation != "edit" || !say.isNullOrBlank()) &&
                    runtime.tasks.editIncoming(id, expectedSay, if (operation == "delete") null else say)
                buildJsonObject { put("ok", changed); put("message", if (changed) "任务已更新" else "任务不存在或内容已变化，请重新查看") }
            }
            else -> buildJsonObject { put("ok", false); put("message", "操作无效") }
        }
    } catch (_: Exception) {
        buildJsonObject { put("ok", false); put("message", "无法读取或保存通话任务") }
    }
}
