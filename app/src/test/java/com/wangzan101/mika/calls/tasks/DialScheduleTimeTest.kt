package com.wangzan101.mika.calls.tasks

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class DialScheduleTimeTest {
    private val now = ZonedDateTime.parse("2026-11-13T13:00:00+08:00[Asia/Singapore]")
    @Test fun resolvesVoiceTimeAndExplicitDate() {
        assertEquals("2026-11-13 13:55", DialScheduleTime.label(DialScheduleTime.resolve("下午1点55分", now)!!))
        assertEquals("2026-11-14 08:30", DialScheduleTime.label(DialScheduleTime.resolve("明天上午8点30分", now)!!))
        assertEquals("2026-11-15 09:20", DialScheduleTime.label(DialScheduleTime.resolve("2026-11-15 09:20", now)!!))
    }
    @Test fun rejectsPastAndUnclearTime() {
        assertNull(DialScheduleTime.resolve("今天中午12:00", now))
        assertNull(DialScheduleTime.resolve("过一会", now))
        assertNull(DialScheduleTime.resolve("明天 25:00", now))
    }
}
