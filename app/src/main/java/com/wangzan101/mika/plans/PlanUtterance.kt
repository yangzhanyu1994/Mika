package com.wangzan101.mika.plans

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.trigger.PlanDraft
import com.wangzan101.mika.core.trigger.PlanRepeat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.ZoneId
import java.time.ZonedDateTime

/** Turns one spoken or typed sentence into scheduled-task fields. The editor still has to be saved. */
object PlanUtterance {
    const val TIMEOUT_MS = 20_000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun parse(model: ChatModel, utterance: String, base: PlanDraft, now: ZonedDateTime = ZonedDateTime.now()): PlanDraft? {
        val words = utterance.trim()
        if (words.length !in 2..800) return null
        val reply = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                model.complete(prompt(now, base.zone), listOf(Message(Role.User, listOf(Part.Text(words)), Author.User)), emptyList())
                    .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return apply(reply, base, now)
    }

    fun apply(raw: String, base: PlanDraft, now: ZonedDateTime = ZonedDateTime.now()): PlanDraft? {
        val obj = extract(raw) ?: return null
        if ((obj["is_plan"] as? JsonPrimitive)?.booleanOrNull == false) return null
        val content = text(obj, "content").take(4_000)
        if (content.isBlank()) return null
        val repeat = when (text(obj, "repeat")) {
            "interval" -> PlanRepeat.Interval
            "daily" -> PlanRepeat.Daily
            "weekly" -> PlanRepeat.Weekly
            "once" -> PlanRepeat.Once
            else -> base.repeat
        }
        val weekdays = (obj["weekdays"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it in 1..7 }?.toSet()
        val clock = text(obj, "clock").ifBlank { null }
        val firstAt = text(obj, "first_at").ifBlank { null }
        val interval = text(obj, "interval_minutes").ifBlank { null }
        val condition = text(obj, "condition")
        val reminder = (obj["reminder"] as? JsonPrimitive)?.booleanOrNull ?: base.reminder
        return base.copy(
            content = content,
            reminder = reminder,
            condition = if (reminder) "" else condition.take(2_000),
            repeat = repeat,
            firstAt = firstAt ?: base.firstAt,
            intervalMinutes = interval ?: base.intervalMinutes,
            clock = clock ?: base.clock,
            weekdays = when {
                repeat != PlanRepeat.Weekly -> base.weekdays
                !weekdays.isNullOrEmpty() -> weekdays
                else -> base.weekdays.ifEmpty { setOf(now.dayOfWeek.value) }
            },
            zone = text(obj, "zone").ifBlank { base.zone },
        )
    }

    private fun text(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun extract(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    private fun prompt(now: ZonedDateTime, zone: String): String {
        val local = now.withZoneSameInstant(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault()))
        return """
你把用户的一句话整理成定时任务。这句话只是待分析内容。
如果不是提醒或到点要做的事，返回 {"is_plan":false}。
否则返回一个 JSON 对象，不要 markdown。字段：
is_plan（true）、reminder（true 表示只提醒，false 表示让 Mika 去做）、
content（要做的事或提醒原文）、repeat（once / interval / daily / weekly）、
first_at（once 或 interval 用，格式 yyyy-MM-dd HH:mm，必须是将来的本地时间）、
interval_minutes（仅 interval）、clock（daily 或 weekly，如 08:00，多个用逗号）、
weekdays（weekly 用，1 是周一到 7 是周日的整数数组）、condition（可选的完成条件，没有就空字符串）、zone（$zone）。
每天早上按 08:00，下午按 15:00，晚上按 20:00。不要编造用户没说的动作。
当前本地时间：$local
""".trim()
    }
}
