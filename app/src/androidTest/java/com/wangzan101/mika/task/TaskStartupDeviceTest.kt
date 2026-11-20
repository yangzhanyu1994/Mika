package com.wangzan101.mika.task

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runs the production service and loop with a local scripted model and no phone control. */
@RunWith(AndroidJUnit4::class)
class TaskStartupDeviceTest {
    @get:Rule val foreground = createAndroidComposeRule<MainActivity>()

    @Test
    fun taskCompletesThroughAgentLoop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tasks = testTasks
        val model = ScriptedModel()
        val main = tasks.modelFactory
        val light = tasks.lightModelFactory
        val vision = tasks.visionModelFactory
        try {
            assertNotNull(pollUntil(10_000) { true.takeIf { !DeviceTaskState.busy.value } })
            tasks.modelFactory = { _, _ -> model }
            tasks.lightModelFactory = { _, _ -> null }
            tasks.visionModelFactory = { _, _ -> null }

            TaskService.start(context, "Reply with done", "task-startup-${UUID.randomUUID()}")
            assertEquals(1, model.awaitCall().number)
            model.answer("done")
            val end = pollUntil(15_000) {
                DeviceTaskState.state.value.takeIf {
                    it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled
                }
            }
            assertTrue("Unexpected task result: $end", end is TaskState.Succeeded && end.answer == "done")
            assertNotNull(pollUntil(10_000) { true.takeIf { !DeviceTaskState.busy.value } })
        } finally {
            if (DeviceTaskState.busy.value) TaskService.cancel(context)
            tasks.modelFactory = main
            tasks.lightModelFactory = light
            tasks.visionModelFactory = vision
        }
    }
}
