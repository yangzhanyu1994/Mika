package com.wangzan101.mika.calls.tasks

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Resolve an explicit local date and time. Ambiguous or past times are not silently scheduled. */
internal object DialScheduleTime {
    private val clock = Regex("""(上午|下午|晚上|中午|凌晨)?\s*(\d{1,2})\s*(?::|点|时)\s*(\d{1,2})?\s*分?""")
    private val date = Regex("""(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?""")
    fun resolve(text: String, now: ZonedDateTime): ZonedDateTime? {
        val input = text.trim()
        val match = clock.find(input) ?: return null
        var hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val period = match.groupValues[1]
        if (period.isNotEmpty() && hour !in 1..12) return null
        if (period in setOf("下午", "晚上", "中午") && hour < 12) hour += 12
        if (period in setOf("上午", "凌晨") && hour == 12) hour = 0
        val explicit = date.find(input)
        val day = try {
            when {
                explicit != null -> LocalDate.of(explicit.groupValues[1].toInt(), explicit.groupValues[2].toInt(), explicit.groupValues[3].toInt())
                input.contains("后天") -> now.toLocalDate().plusDays(2)
                input.contains("明天") -> now.toLocalDate().plusDays(1)
                else -> now.toLocalDate()
            }
        } catch (_: Exception) { return null }
        val local = day.atTime(hour, minute)
        // Reject a missing or repeated local time at a daylight-saving transition.
        val offsets = now.zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return null
        val result = ZonedDateTime.ofStrict(local, offsets.single(), now.zone)
        return result.takeIf { it.toInstant().isAfter(now.toInstant()) }
    }
    fun label(time: ZonedDateTime): String = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
