package com.wangzan101.mika.capsule

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.OrbMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleMotionTest {
    @Test fun theFloatingMarkFollowsTheTaskAndIdleRestoresTheLogo() {
        assertEquals(OrbMotion.Connecting, floatingOrb(CapsuleKind.Running, TaskState.Running(step = 0, note = "")))
        assertEquals(OrbMotion.Thinking, floatingOrb(CapsuleKind.Running, TaskState.Running(step = 2, note = "")))
        assertEquals(OrbMotion.Searching, floatingOrb(CapsuleKind.Running, TaskState.Running(step = 2, note = "", tool = "web_search")))
        assertEquals(OrbMotion.Working, floatingOrb(CapsuleKind.Running, TaskState.Running(step = 2, note = "", tool = "phone_tap")))
        assertEquals(OrbMotion.Waiting, floatingOrb(CapsuleKind.Held, TaskState.Held(GateReason("paused"))))
        assertNull(floatingOrb(CapsuleKind.Idle, TaskState.Idle))
        assertNull(floatingOrb(CapsuleKind.Done, TaskState.Succeeded("好")))
        assertNull(floatingOrb(CapsuleKind.Notice, TaskState.Idle))
    }
}
