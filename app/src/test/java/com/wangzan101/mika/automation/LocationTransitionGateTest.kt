package com.wangzan101.mika.automation

import org.junit.Assert.*
import org.junit.Test

class LocationTransitionGateTest {
    @Test fun requiresARealCrossingAndIgnoresUncertainFixes() {
        val gate = LocationTransitionGate()
        assertNull(gate.observe("home", 300f, 10f, 100f))
        assertNull(gate.observe("home", 105f, 20f, 100f))
        assertEquals(true, gate.observe("home", 50f, 10f, 100f))
        assertNull(gate.observe("home", 40f, 10f, 100f))
        assertNull(gate.observe("home", 95f, 10f, 100f))
        assertEquals(false, gate.observe("home", 200f, 10f, 100f))
    }

    @Test fun rejectsBadFixesAndDoesNotReplayAfterReset() {
        val gate = LocationTransitionGate()
        assertNull(gate.observe("home", Float.NaN, 10f, 100f))
        assertNull(gate.observe("home", 0f, -1f, 100f))
        assertNull(gate.observe("home", 200f, 10f, 100f))
        gate.reset("home")
        assertNull(gate.observe("home", 0f, 1f, 100f))
    }
}
