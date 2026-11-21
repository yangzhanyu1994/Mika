package com.wangzan101.mika.core.clock

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalClockTest {
    @Test fun sampleKeepsZoneAndTheInstantThatWasRead() {
        val instant = Instant.parse("2026-11-21T02:20:00Z")
        val payload = LocalClock.payload(LocalClock.sample(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals(instant.toEpochMilli(), payload.getValue("epochMs").jsonPrimitive.long)
        assertEquals("Asia/Shanghai", payload.getValue("zone").jsonPrimitive.content)
        assertEquals("+08:00", payload.getValue("offset").jsonPrimitive.content)
        assertEquals("2026-11-21T10:20:00+08:00", payload.getValue("local").jsonPrimitive.content)
        assertEquals(instant.toString(), payload.getValue("sampledAt").jsonPrimitive.content)
    }
}
