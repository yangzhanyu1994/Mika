package com.wangzan101.mika.core.calendar

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTodayTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-21T02:20:00Z").toEpochMilli()

    @Test fun missingGrantIsNotAnEmptyDay() {
        val day = readCalendarDay(false, now, zone) { _, _ -> error("query should not run") }
        assertEquals("no_permission", day.failure)
        assertTrue(day.events.isEmpty())
        val payload = calendarPayload(day)
        assertEquals(false, payload.getValue("ok").jsonPrimitive.boolean)
        assertEquals(false, payload.getValue("empty").jsonPrimitive.boolean)
    }

    @Test fun withdrawnGrantIsDistinctFromAnEmptyDay() {
        val day = readCalendarDay(true, now, zone) { _, _ -> throw SecurityException("revoked") }
        assertEquals("permission_revoked", day.failure)
        assertEquals(false, calendarPayload(day).getValue("empty").jsonPrimitive.boolean)
    }

    @Test fun emptyDayStaysEmptyAndUsesTheLocalWindow() {
        val day = readCalendarDay(true, now, zone) { start, end ->
            assertEquals(Instant.parse("2026-11-20T16:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2026-11-21T16:00:00Z").toEpochMilli(), end)
            emptyList()
        }
        assertEquals(null, day.failure)
        val payload = calendarPayload(day)
        assertEquals(true, payload.getValue("ok").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("empty").jsonPrimitive.boolean)
        assertEquals("2026-11-21T00:00:00+08:00", payload.getValue("windowStart").jsonPrimitive.content)
        assertEquals("2026-11-22T00:00:00+08:00", payload.getValue("windowEnd").jsonPrimitive.content)
    }

    @Test fun eventsAreOrderedAndKeepTheirOwnZone() {
        val early = CalendarEvent("早会", Instant.parse("2026-11-21T01:00:00Z").toEpochMilli(), Instant.parse("2026-11-21T02:00:00Z").toEpochMilli(), false, "UTC", null)
        val later = CalendarEvent("晚饭", Instant.parse("2026-11-21T10:00:00Z").toEpochMilli(), Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(), false, "Asia/Shanghai", "家")
        val day = readCalendarDay(true, now, zone) { _, _ -> listOf(later, early) }
        assertEquals(listOf("早会", "晚饭"), day.events.map { it.title })
        val events = calendarPayload(day).getValue("events").jsonArray
        assertEquals("UTC", events[0].jsonObject.getValue("eventZone").jsonPrimitive.content)
        assertEquals("2026-11-21T09:00:00+08:00", events[0].jsonObject.getValue("begin").jsonPrimitive.content)
    }
}
