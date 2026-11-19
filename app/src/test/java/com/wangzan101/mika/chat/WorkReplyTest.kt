package com.wangzan101.mika.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkReplyTest {
    @Test fun summaryAndSupplementaryDetailsShareOneAnswer() {
        val summary = "车辆已熄火，挂 P 挡。车锁状态未能确认，剩余油量 7 L。"
        val details = "### 车辆状态\n\n- 蓄电池电压 12.4 V"
        val reply = WorkReply.from("$summary\n\n${WorkReply.DETAILS_MARKER}\n\n$details")
        assertEquals(summary, reply.summary)
        assertEquals("$summary\n\n$details", reply.full)
    }

    @Test fun streamingDoesNotFlashSeparatorOrDetails() {
        assertEquals("结果", WorkReply.from("结果\n\n<!-- mika:det", streaming = true).summary)
        assertEquals("结果", WorkReply.from("结果\n\n${WorkReply.DETAILS_MARKER}\n明细", streaming = true).summary)
    }

    @Test fun legacyAnswersStayAvailableInFull() {
        val short = "已完成。"
        assertEquals(WorkReply(short, short), WorkReply.from(short))
        val legacy = "确认了车辆状态。\n\n" + "详细读数\n".repeat(60)
        assertEquals(WorkReply("确认了车辆状态。", legacy), WorkReply.from(legacy))
        val longLine = "😀".repeat(200)
        val reply = WorkReply.from(longLine)
        assertEquals(longLine, reply.full)
        assertTrue(reply.summary.endsWith("…"))
        assertEquals("😀".repeat(120) + "…", reply.summary)
    }

    @Test fun structuredOutputAndMarkersInsideCodeArePreserved() {
        val fenced = "```html\n${WorkReply.DETAILS_MARKER}\n```"
        assertEquals(WorkReply(fenced, fenced), WorkReply.from(fenced))
        val json = "{\"text\":\"${"长".repeat(300)}\"}"
        assertEquals(WorkReply(json, json), WorkReply.from(json))
    }

    @Test fun missingSummaryFallsBackToDetails() {
        val reply = WorkReply.from("${WorkReply.DETAILS_MARKER}\n有结果。")
        assertEquals(WorkReply("有结果。", "有结果。"), reply)
    }
}
