package com.wangzan101.mika.core.location

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationPolicyTest {
    private val here = LocationSample(31.2, 121.5, 18f, 1_000, "gps", fromCache = false)
    private val stored = here.copy(ageMs = 30_000, fromCache = true)
    private val old = here.copy(ageMs = DeviceLocationPolicy.FRESH_MS + 1, fromCache = true)

    @Test fun foregroundUsesAFreshReadingAndMarksCache() {
        val live = DeviceLocationPolicy.decide(
            LocationAccess.Fine, true, false, false, RunOrigin.Ui, Attendance.Present, here, stored,
        )
        assertNull(live.failure)
        assertEquals(false, live.cached)
        val cache = DeviceLocationPolicy.decide(
            LocationAccess.Coarse, true, false, false, RunOrigin.Ui, Attendance.Present, null, stored,
        )
        assertTrue(cache.cached)
        assertEquals(31.2, cache.sample!!.latitude, 0.0)
    }

    @Test fun staleCacheIsNotReportedAsCurrent() {
        val decision = DeviceLocationPolicy.decide(
            LocationAccess.Fine, true, true, true, RunOrigin.Ui, Attendance.Present, null, old,
        )
        assertEquals("no_fix", decision.failure)
        assertNull(decision.sample)
    }

    @Test fun missingPermissionServicesAndBackgroundAreDistinct() {
        assertEquals("no_permission", denied(LocationAccess.None, true, true, true).failure)
        assertEquals("services_off", denied(LocationAccess.Fine, false, true, true).failure)
        assertEquals("needs_background", DeviceLocationPolicy.decide(
            LocationAccess.Fine, true, false, true, RunOrigin.Trigger, Attendance.Unattended, here, null,
        ).failure)
        assertEquals("auto_off", DeviceLocationPolicy.decide(
            LocationAccess.Fine, true, true, false, RunOrigin.Trigger, Attendance.Unattended, here, null,
        ).failure)
    }

    @Test fun backgroundTaskCanUseAFreshFixOnlyWhenAutoAndAlwaysAllow() {
        val decision = DeviceLocationPolicy.decide(
            LocationAccess.Fine, true, true, true, RunOrigin.Trigger, Attendance.Unattended, here, null,
        )
        assertNull(decision.failure)
        assertEquals(false, decision.cached)
    }

    private fun denied(access: LocationAccess, services: Boolean, background: Boolean, auto: Boolean) =
        DeviceLocationPolicy.decide(access, services, background, auto, RunOrigin.Ui, Attendance.Present, here, null)
}
