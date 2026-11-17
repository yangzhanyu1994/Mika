package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRelockTest {
    @Test fun keepsObligationUntilSystemConfirmsLock() {
        val c = LocalRelock()
        c.unlocked(7)
        assertEquals(LocalRelock.Decision.WAIT, c.decide(true, false, 7, true))
        assertEquals(LocalRelock.Decision.LOCK, c.decide(false, false, 7, true))
        // A rejected or unconfirmed global action must remain retryable.
        assertTrue(c.hasPending())
        assertEquals(LocalRelock.Decision.LOCK, c.decide(false, false, 7, true))
        c.confirmed()
        assertFalse(c.hasPending())
        assertEquals(LocalRelock.Decision.NONE, c.decide(false, false, 7, true))
    }

    @Test fun onlyAllowsRelockAfterAnIdleGracePeriod() {
        val c = LocalRelock()
        c.unlocked(3)
        assertEquals(LocalRelock.Decision.WAIT_IDLE, c.decide(false, false, 4, true))
        assertFalse(c.acceptIdle(4, 5))
        assertTrue(c.hasPending())
        assertTrue(c.acceptIdle(5, 5))
        assertEquals(LocalRelock.Decision.LOCK, c.decide(false, false, 5, true))
        assertEquals(LocalRelock.Decision.WAIT_IDLE, c.decide(false, false, 6, true))
    }

    @Test fun unavailableServiceWarnsWithoutDroppingObligation() {
        val c = LocalRelock()
        c.unlocked(5)
        assertEquals(LocalRelock.Decision.WARN, c.decide(false, false, 5, false))
        assertTrue(c.hasPending())
        assertEquals(LocalRelock.Decision.NONE, c.decide(false, true, 5, false))
        assertFalse(c.hasPending())
    }
}
