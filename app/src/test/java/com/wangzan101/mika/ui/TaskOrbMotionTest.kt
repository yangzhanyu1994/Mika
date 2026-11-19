package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOrbMotionTest {
    @Test fun realTaskProgressSelectsTheSharedOrb() {
        val cases = listOf(
            TaskState.Running(0, "") to OrbMotion.Connecting,
            TaskState.Running(1, "") to OrbMotion.Thinking,
            TaskState.Running(1, "", tool = "web_search") to OrbMotion.Searching,
            TaskState.Running(1, "", tool = "observe") to OrbMotion.Observing,
            TaskState.Running(1, "", tool = "phone_tap") to OrbMotion.Working,
            TaskState.Running(2, "", preview = "Result") to OrbMotion.Writing,
            TaskState.Running(2, "", progress = RunProgress.WaitingForApp("Browser")) to OrbMotion.Waiting,
            TaskState.WaitingInput("Which city?") to OrbMotion.Waiting,
            TaskState.Succeeded("Done") to OrbMotion.Complete,
        )
        for ((state, motion) in cases) assertEquals(motion, state.orbMotion)
    }
}
