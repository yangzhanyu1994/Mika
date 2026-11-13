package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Scene runs use the same notification formatter as other trigger runs. */
class SceneResultNotificationTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun completedNotificationContainsActualAnswer() {
        val answer = "车已锁，车窗已关"
        assertEquals("任务完成" to answer, TaskService.describe(TaskState.Succeeded(answer)))
    }

    @Test fun failedOrUnverifiedResultsAreNotReportedAsConfirmed() {
        assertEquals("任务没有完成" to "无法读取车窗状态", TaskService.describe(TaskState.Failed("无法读取车窗状态", FailKind.Environment)))
        val (title, body) = TaskService.describe(TaskState.Succeeded("车窗状态未确认", verified = false, note = "无法核实"))
        assertEquals("办完了,但没能核实", title)
        assertTrue(body.contains("车窗状态未确认"))
    }
}
