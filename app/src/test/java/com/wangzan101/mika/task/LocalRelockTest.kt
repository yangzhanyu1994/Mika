package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRelockTest {
    @Test fun waitsForAllTasksAndLocksOnce() {
        val c = LocalRelock()
        c.unlocked(7)
        assertEquals(LocalRelock.Decision.WAIT, c.decide(true, false, 7, true))
        assertEquals(LocalRelock.Decision.LOCK, c.decide(false, false, 7, true))
        assertEquals(LocalRelock.Decision.NONE, c.decide(false, false, 7, true))
    }

    @Test fun userInteractionOrUnavailableServiceRequiresWarning() {
        val c = LocalRelock()
        c.unlocked(3)
        assertEquals(LocalRelock.Decision.WARN, c.decide(false, false, 4, true))
        c.unlocked(5)
        assertEquals(LocalRelock.Decision.WARN, c.decide(false, false, 5, false))
        c.unlocked(6)
        assertEquals(LocalRelock.Decision.NONE, c.decide(false, true, 6, false))
    }
}
