package com.wangzan101.mika.automation

import org.junit.Assert.*
import org.junit.Test

class WifiTransitionGateTest {
    @Test fun switchingNetworksProducesBothEdgesButNotDuplicates() {
        val gate = WifiTransitionGate()
        assertNull(gate.observe("home"))
        assertNull(gate.observe("home"))
        assertEquals(WifiTransitionGate.Transition("home", "office"), gate.observe("office"))
        assertEquals(WifiTransitionGate.Transition("office", null), gate.observe(null))
        assertNull(gate.observe(null))
        assertEquals(WifiTransitionGate.Transition(null, "home"), gate.observe("home"))
    }

    @Test fun initialObservationAndRestartAreNotTransitions() {
        val gate = WifiTransitionGate()
        assertNull(gate.observe(null))
        gate.reset()
        assertNull(gate.observe("home"))
    }
}
