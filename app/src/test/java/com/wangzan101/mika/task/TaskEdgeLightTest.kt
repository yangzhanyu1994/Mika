package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskEdgeLightTest {
    @Test fun completionHasFiveDistinctFlashesThenGoesDark() {
        var flashes = 0
        var wasLit = false
        for (elapsed in 0L..(TaskEdgeLight.SUCCESS_DURATION_MS + 1_000)) {
            val lit = TaskEdgeLight.completionAlpha(elapsed) > 0f
            if (lit && !wasLit) flashes++
            wasLit = lit
        }
        assertEquals(5, flashes)
        assertEquals(0f, TaskEdgeLight.completionAlpha(TaskEdgeLight.SUCCESS_DURATION_MS), 0f)
    }
}
