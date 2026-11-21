package com.wangzan101.mika.core.calendar

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CalendarEvent(
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val eventZone: String?,
    val location: String?,
)

data class CalendarDay(
    val zone: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val failure: String? = null,
    val events: List<CalendarEvent> = emptyList(),
)

fun interface CalendarQuery {
    @Throws(SecurityException::class)
    fun query(startMs: Long, endMs: Long): List<CalendarEvent>
}

interface CalendarReadPort {
    fun read(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): CalendarDay
}

/**
 * Reads instances that overlap the local calendar day of [nowMs].
 * [no_permission] is a missing grant. [permission_revoked] is a grant that disappeared at query time.
 * An empty list is a successful read, not a failure. The window is the device zone's local midnight to next midnight.
 */
fun readCalendarDay(
    hasPermission: Boolean,
    nowMs: Long,
    zone: ZoneId,
    query: CalendarQuery,
): CalendarDay {
    val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    if (!hasPermission) return CalendarDay(zone.id, start, end, failure = "no_permission")
    val events = try {
        query.query(start, end)
    } catch (_: SecurityException) {
        return CalendarDay(zone.id, start, end, failure = "permission_revoked")
    }
    return CalendarDay(zone.id, start, end, events = events.sortedBy { it.beginMs })
}

internal fun calendarPayload(day: CalendarDay): JsonObject {
    val zone = runCatching { ZoneId.of(day.zone) }.getOrDefault(ZoneId.of("UTC"))
    val format = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    return buildJsonObject {
        put("ok", day.failure == null)
        day.failure?.let { put("failure", it) }
        put("zone", day.zone)
        put("windowStartMs", day.windowStartMs)
        put("windowEndMs", day.windowEndMs)
        put("windowStart", format.format(Instant.ofEpochMilli(day.windowStartMs).atZone(zone)))
        put("windowEnd", format.format(Instant.ofEpochMilli(day.windowEndMs).atZone(zone)))
        put("empty", day.failure == null && day.events.isEmpty())
        put("events", buildJsonArray {
            day.events.forEach { event ->
                add(buildJsonObject {
                    put("title", event.title)
                    put("beginMs", event.beginMs)
                    put("endMs", event.endMs)
                    put("begin", format.format(Instant.ofEpochMilli(event.beginMs).atZone(zone)))
                    put("end", format.format(Instant.ofEpochMilli(event.endMs).atZone(zone)))
                    put("allDay", event.allDay)
                    event.eventZone?.let { put("eventZone", it) }
                    event.location?.let { put("location", it) }
                })
            }
        })
    }
}

internal class CalendarCapability(val port: CalendarReadPort) : Capability {
    override val id = "calendar"
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = calendarTools
    override fun sections(env: com.wangzan101.mika.core.capability.PromptEnv): List<String> = listOf(
        "When the user asks what is on the calendar today, call read_calendar_today. " +
            "no_permission and permission_revoked are final: do not work around them by opening another app or asking the model to guess. " +
            "empty means the local day has no events. Use the returned zone and window; do not treat an event that only touches the next local day as today.",
    )
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    companion object { val KEY = CapabilityKey<CalendarCapability>("calendar") }
}

internal val calendarTools: List<AgentTool> = listOf(ReadCalendarToday)

private object ReadCalendarToday : AgentTool {
    override val capability = "calendar"
    override val offScreen = true
    override val risk = Risk.Read
    override val verb = Verb.ReadCalendar
    override val label = "今日日程"
    override val logged = false
    override val def = ToolDef("read_calendar_today", Prompts.tool("read_calendar_today"), emptyMap(), emptyList())
    override fun available(ctx: ToolContext) = ctx[CalendarCapability.KEY] != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        ToolExecution(calendarPayload(ctx[CalendarCapability.KEY]!!.port.read()))
}
