package com.wangzan101.mika.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantSpokenSummaryTest {
    @Test fun readsTheWholeReplyAndRemovesBoldAndFullWidthStars() {
        assertEquals(
            "北京市今天晴，气温 18°C。\n\n明天可能有雨，出门记得带伞。",
            assistantReadoutText(
                "**北京市今天**晴，气温 18°C。\n\n＊＊明天可能有雨，出门记得带伞。", "任务完成",
            ),
        )
    }

    @Test fun retainsAllResultRowsWithoutBulletGlyphs() {
        assertEquals(
            "气温：2°C\n体感：-2°C\n天气：晴\n湿度：32%",
            assistantReadoutText("- 气温：2°C\n- 体感：-2°C\n- 天气：晴\n- 湿度：32%", "任务完成"),
        )
    }

    @Test fun fallsBackOnlyWhenNoSpeechTextRemains() {
        assertEquals("任务完成", assistantReadoutText("＊＊", "任务完成"))
        assertEquals("说明".repeat(130), assistantReadoutText("说明".repeat(130), "任务完成"))
    }
}
