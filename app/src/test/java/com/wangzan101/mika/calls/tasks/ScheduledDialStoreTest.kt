package com.wangzan101.mika.calls.tasks

import org.junit.Assert.*
import org.junit.Test

class ScheduledDialStoreTest {
    private class Memory : CallTaskBackend {
        var data: String? = null
        override fun read() = data
        override fun write(value: String): Boolean { data = value; return true }
    }

    @Test fun occurrenceIsClaimedOnceAndSurvivesRestart() {
        val backend = Memory()
        val store = CallTaskStore(backend, now = { 2000L }, newId = { "task" })
        store.save(CallAssistantTask("", CallTaskTexts.KIND_DIAL, "测试", say = "你好", number = "15551234567",
            dialAtMs = 1000L, dialSimId = "sim-1", dialScheduleState = "scheduled"))
        assertNull(store.claimScheduled("task", 999L))
        assertNotNull(store.claimScheduled("task", 1000L))
        assertNull(store.claimScheduled("task", 1000L))
        val reopened = CallTaskStore(backend, now = { 2000L })
        assertNull(reopened.claimScheduled("task", 1000L))
        assertEquals("dispatching", reopened.snapshot().single().dialScheduleState)
        reopened.finishScheduled("task", 1000L, false, "无可用号卡")
        assertEquals("failed", reopened.snapshot().single().dialScheduleState)
        assertFalse(reopened.snapshot().single().dialed)
    }

    @Test fun rejectsEarlyAlarmsAndEditsWhileDispatching() {
        val backend = Memory()
        var now = 500L
        val store = CallTaskStore(backend, now = { now }, newId = { "task" })
        val task = store.save(CallAssistantTask("", CallTaskTexts.KIND_DIAL, "测试", say = "你好", number = "15551234567", dialAtMs = 1000L, dialSimId = "sim-1", dialScheduleState = "scheduled"))
        assertNull(store.claimScheduled(task.id, 1000L))
        now = 1000L
        assertNotNull(store.claimScheduled(task.id, 1000L))
        assertTrue(runCatching { store.save(task) }.isFailure)
        assertTrue(runCatching { store.remove(task.id) }.isFailure)
    }
}
