package com.wangzan101.mika.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantSpokenSummaryTest {
    @Test fun keepsTheOpeningAndSkipsWrittenDetails() {
        assertEquals("设好了明早八点的闹钟。", assistantSpokenSummary(
            "设好了明早八点的闹钟。\n\n闹钟详情", "任务完成",
        ))
    }

    @Test fun keepsAShortRequiredDecisionAndDoesNotSplitDecimalPrices() {
        assertEquals("共 ¥49.90。 请确认是否提交。", assistantSpokenSummary(
            "共 ¥49.90。请确认是否提交。其他内容。", "任务完成",
        ))
    }

    @Test fun usesVerificationStatusWhenNoShortNoteIsAvailable() {
        assertEquals("有未核实项", assistantSpokenSummary("说明".repeat(130), "有未核实项"))
    }
}
