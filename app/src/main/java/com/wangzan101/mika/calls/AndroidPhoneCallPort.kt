package com.wangzan101.mika.calls

import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import kotlinx.serialization.json.*

/** One native runtime for the local agent, UI and authenticated MCP endpoint. */
class AndroidPhoneCallPort(private val runtime: CallRuntime, private val context: android.content.Context) : PhoneCallPort {
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
        config.defaultOutgoingSimId?.let { put("default_outgoing_sim_id", it) }
        put("default_outgoing_sim_available", config.defaultOutgoingSimId?.let { id -> state.simOptions.any { it.id == id } } ?: false)
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

    override suspend fun resolveContact(name: String): JsonObject {
        val book = PhoneContacts(context)
        if (!book.granted()) return buildJsonObject { put("ok", false); put("message", "请先授予通讯录权限，或提供电话号码") }
        val matches = book.entries().filter { it.name.equals(name.trim(), ignoreCase = true) }
        val numbers = matches.map { CallPolicy.outgoingNumber(it.number) }.filterNotNull().distinct()
        return if (numbers.size == 1) buildJsonObject { put("ok", true); put("name", matches.first().name); put("number", numbers.single()) }
        else buildJsonObject { put("ok", false); put("message", if (numbers.isEmpty()) "没有找到完全匹配的联系人，请确认姓名或号码" else "该联系人有多个号码，请确认使用哪个号码") }
    }

    override suspend fun scheduleDial(number: String, target: String, goal: String, constraints: String, localTime: String, simId: String?): JsonObject {
        fun failure(message: String) = buildJsonObject { put("ok", false); put("message", message) }
        val normalized = CallPolicy.outgoingNumber(number) ?: return failure("电话号码无效")
        val emergency = runCatching { context.getSystemService(android.telephony.TelephonyManager::class.java)?.isEmergencyNumber(normalized) }.getOrNull()
        if (emergency != false) return failure("无法确认号码不是紧急电话，未创建任务")
        val local = runCatching { java.time.LocalDateTime.parse(localTime, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull()
            ?: return failure("请确认明确的预约日期和时间")
        val zone = java.time.ZoneId.systemDefault()
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return failure("预约时间存在歧义，请重新指定")
        val due = java.time.ZonedDateTime.ofStrict(local, offsets.single(), zone).toInstant().toEpochMilli()
        if (due <= System.currentTimeMillis()) return failure("预约时间必须晚于当前时间")
        if (!runtime.settings.state.value.enabled) return failure("请先启用 AI 电话助理")
        if (!com.wangzan101.mika.task.TriggerRuntime.canScheduleExactly(context)) return failure("请先允许精确闹钟")
        runtime.refresh()
        val sim = CallPolicy.chooseOutgoingSim(runtime.state.value.simOptions, simId, runtime.settings.state.value.defaultOutgoingSimId)
            ?: return failure("请先选择可用的拨号卡")
        val brief = runCatching { CallTaskBrief(goal, emptyList(), target, constraints).validatedSnapshot() }.getOrNull()
            ?: return failure("电话任务内容无效或过长")
        return synchronized(this) {
          try {
            if (!runtime.tasks.load()) return@synchronized failure("无法读取已有通话任务")
            val existing = runtime.tasks.snapshot().firstOrNull { it.kind == com.wangzan101.mika.calls.tasks.CallTaskTexts.KIND_DIAL &&
                it.dialScheduleState in setOf("scheduled", "dispatching", "submitted") && it.number == normalized && it.target == target &&
                it.say == brief.goal && it.cannotPromise == brief.constraints && it.dialAtMs == due && it.dialSimId == sim.id }
            if (existing != null) return@synchronized buildJsonObject { put("ok", true); put("created", false); put("id", existing.id); put("message", "已有相同的预约拨号任务，未重复创建"); put("local_time", localTime) }
            val task = com.wangzan101.mika.calls.tasks.CallAssistantTask(id = "", kind = com.wangzan101.mika.calls.tasks.CallTaskTexts.KIND_DIAL,
                title = target.take(64), target = target, say = brief.goal, cannotPromise = brief.constraints,
                number = normalized, windowLabel = localTime, dialAtMs = due, dialSimId = sim.id, dialScheduleState = "scheduled")
            val saved = runtime.tasks.save(task)
            try { com.wangzan101.mika.calls.tasks.ScheduledDial.schedule(context, saved) }
            catch (e: Exception) { runtime.tasks.save(saved.copy(dialScheduleState = "failed", dialScheduleMessage = "无法设置定时拨号")); throw e }
            buildJsonObject { put("ok", true); put("message", "已创建定时 AI 拨打任务"); put("id", saved.id); put("target", target); put("local_time", localTime); put("sim", sim.label) }
          } catch (_: Exception) { failure("未能创建预约任务，请到 AI 电话助理检查；不要重复创建") }
        }
    }

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
