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

    @Test fun receiptAndTimestampDoNotHideActualVehicleFindings() {
        val opening = "车况已经读到了，数据更新时间是 **2026-11-19 18:30:47**（页面「车辆状态」里的「更新于」）。"
        val facts = "- 发动机：熄火\n- 挡位：P 挡\n- 车门和车窗：关闭"
        val details = "**车辆状态**\n\n$facts\n- 车辆数据来源：车主应用"
        val full = "$opening\n\n$details"
        assertEquals(WorkReply(facts, full), WorkReply.from(full))
        assertEquals(facts, WorkReply.from("$opening\n$details").summary)
        assertEquals(WorkReply(facts, full), WorkReply.from("$opening\n\n${WorkReply.DETAILS_MARKER}\n\n$details"))
    }

    @Test fun missingFindingsAreNotInventedAndAnUnconfirmedFactIsKept() {
        val opening = "车况已经读到了，数据更新时间是 **18:30:47**。"
        assertEquals(WorkReply(opening, opening), WorkReply.from(opening))

        val summary = "$opening 车锁状态未能确认。"
        val answer = "$summary\n\n${WorkReply.DETAILS_MARKER}\n- 挡位：P 挡"
        assertEquals(summary, WorkReply.from(answer).summary)
    }

    @Test fun genericReceiptFallbackIsNotLimitedToVehicles() {
        val opening = "I retrieved the requested data. Last updated at 09:12."
        val details = "### Departures\n\n- Next train: 09:42\n- Platform: 3\n- Delay: 8 minutes"
        val reply = WorkReply.from("$opening\n\n${WorkReply.DETAILS_MARKER}\n\n$details")
        assertEquals("- Next train: 09:42\n- Platform: 3\n- Delay: 8 minutes", reply.summary)
    }
}
