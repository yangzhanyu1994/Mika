package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEdgeLightTest {
    @Test fun blueBrightnessDimsAndReturnsOverOneCycle() {
        assertEquals(1f, TaskEdgeLight.breathingAlpha(0), 0.0001f)
        assertEquals(0.57f, TaskEdgeLight.breathingAlpha(725), 0.0001f)
        assertEquals(0.14f, TaskEdgeLight.breathingAlpha(1_450), 0.0001f)
        assertEquals(0.57f, TaskEdgeLight.breathingAlpha(2_175), 0.0001f)
        assertEquals(1f, TaskEdgeLight.breathingAlpha(2_900), 0.0001f)
        assertEquals(TaskEdgeLight.breathingAlpha(725), TaskEdgeLight.breathingAlpha(3_625), 0.0001f)
    }

    @Test fun completedTaskIsEligibleWithoutWaitingForBackgroundJudgment() {
        val result = TaskState.Succeeded("done")
        assertTrue(TaskEdgeLight.showsCompletion(result, conditionCheck = false))
        assertFalse(TaskEdgeLight.showsCompletion(result, conditionCheck = true))
        assertFalse(TaskEdgeLight.showsCompletion(result.copy(verified = false), conditionCheck = false))
        assertFalse(TaskEdgeLight.showsCompletion(result.copy(unconfirmed = listOf("unverified")), conditionCheck = false))
        assertFalse(TaskEdgeLight.showsCompletion(TaskState.Failed("error", FailKind.Environment), conditionCheck = false))
        assertFalse(TaskEdgeLight.showsCompletion(TaskState.Cancelled, conditionCheck = false))
    }

    @Test fun relockWaitUsesTheSameDeadlineAsTheCompletionLight() {
        val cue = TaskEdgeLight.Completion("run", startedAt = 1_000L)
        val duration = TaskEdgeLight.SUCCESS_DURATION_MS
        assertEquals(duration, cue.remainingMs(1_000L))
        assertEquals(1L, cue.remainingMs(999L + duration))
        assertEquals(0L, cue.remainingMs(1_000L + duration))
        assertEquals(0L, cue.remainingMs(2_000L + duration))
    }

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
