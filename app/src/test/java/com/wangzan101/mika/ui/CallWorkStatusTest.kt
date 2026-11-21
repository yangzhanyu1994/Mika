package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallWorkStatusTest {
    @Test fun statusComesFromTheTaskAndNotFromAScript() {
        assertEquals("time", callWorkStatus(TaskState.Running(1, "", tool = "read_local_time")) { it })
        assertEquals("calendar", callWorkStatus(TaskState.Running(1, "", tool = "read_calendar_today")) { it })
        assertEquals("weather", callWorkStatus(TaskState.Running(1, "", tool = "read_weather")) { it })
        assertEquals("tool:打开应用", callWorkStatus(TaskState.Running(2, "", tool = "phone_launch_app")) { "打开应用" })
        assertEquals("working", callWorkStatus(TaskState.Running(2, "")) { it })
        assertEquals("confirm", callWorkStatus(TaskState.WaitingInput("?")) { it })
        assertEquals("done", callWorkStatus(TaskState.Succeeded("好")) { it })
        assertEquals("failed", callWorkStatus(TaskState.Failed("没有日历权限", com.wangzan101.mika.core.agent.FailKind.GaveUp)) { it })
        assertEquals("cancelled", callWorkStatus(TaskState.Cancelled) { it })
        assertNull(callWorkStatus(TaskState.Idle) { it })
        assertEquals("waiting", callWorkStatus(TaskState.Held(GateReason("paused"))) { it })
    }
}
