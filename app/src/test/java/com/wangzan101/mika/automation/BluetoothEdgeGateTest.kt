package com.wangzan101.mika.automation

import org.junit.Assert.*
import org.junit.Test

class BluetoothEdgeGateTest {
    @Test fun reconnectCancelsPendingDisconnectAndDuplicateDoesNotRestartTimer() {
        val gate = BluetoothEdgeGate()
        val pending = gate.observe("aa:bb", false, 1000, 60_000)!!
        assertNull(gate.observe("AA:BB", false, 2000, 60_000))
        assertFalse(gate.isDue(pending, 60_999, true))
        gate.observe("AA:BB", true, 3000, 0)
        assertFalse(gate.isDue(pending, 61_000, true))
        val next = gate.observe("AA:BB", false, 70_000, 60_000)!!
        assertTrue(gate.isDue(next, 130_000, true))
        assertFalse(gate.isDue(next, 130_000, false))
    }

    @Test fun differentDevicesAndRestartCannotRevivePendingEdges() {
        val gate = BluetoothEdgeGate()
        val car = gate.observe("car", false, 1, 10)!!
        gate.observe("headset", true, 2, 0)
        assertTrue(gate.isDue(car, 11, true))
        gate.reset()
        assertFalse(gate.isDue(car, 11, true))
    }
}
