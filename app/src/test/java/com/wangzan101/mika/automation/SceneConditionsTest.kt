package com.wangzan101.mika.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneConditionsTest {
    private val wifi = SceneCondition(SceneSignal.Wifi, "Home")
    private val bluetooth = SceneCondition(SceneSignal.Bluetooth, "AB:CD:EF:12:34:56", present = false)

    @Test fun aMidnightWindowIncludesItsStartAndExcludesItsEnd() {
        val window = SceneTimeWindow(22 * 60, 7 * 60)
        assertTrue(window.valid)
        assertTrue(window.contains(22 * 60))
        assertTrue(window.contains(0))
        assertTrue(window.contains(7 * 60 - 1))
        assertFalse(window.contains(7 * 60))
        assertFalse(window.contains(12 * 60))
        assertFalse(SceneTimeWindow(60, 60).valid)
    }

    @Test fun singleAnyAndAllRespectTheWindowAndUnknownDoesNotMeanDisconnected() {
        val all = SceneConditions(listOf(wifi, bluetooth), requireAll = true, window = SceneTimeWindow(60, 120))
        val any = all.copy(requireAll = false)
        val single = all.copy(signals = listOf(bluetooth))
        assertTrue(all.valid)
        assertTrue(single.valid)
        assertEquals(false, any.matches(120) { true })
        assertEquals(true, any.matches(90) { if (it == wifi) true else null })
        assertNull(all.matches(90) { if (it == wifi) true else null })
        assertNull(single.matches(90) { null })
        assertEquals(true, all.matches(90) { it == wifi })
        assertEquals(false, any.matches(90) { it != wifi })
    }

    @Test fun aPendingMatchIsInvalidatedAndRestartDoesNotRefireTheExistingMatch() {
        val gate = SceneMatchGate()
        assertNull(gate.observe(true))
        assertNull(gate.observe(false))
        val pending = requireNotNull(gate.observe(true))
        assertTrue(gate.current(pending))
        assertNull(gate.observe(true))
        assertNull(gate.observe(null))
        assertFalse(gate.current(pending))
        assertNull(gate.observe(true))
        gate.observe(false)
        val next = requireNotNull(gate.observe(true))
        assertFalse(gate.current(pending))
        assertTrue(gate.current(next))
    }

    @Test fun firstReportedBluetoothEdgeCanTriggerWithoutGuessingPreviousState() {
        assertNull(SceneMatchGate().observe(true))
        assertNotNull(SceneMatchGate().observe(true, observedEvent = true))
    }
}
