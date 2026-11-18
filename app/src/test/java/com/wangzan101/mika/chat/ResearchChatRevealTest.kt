package com.wangzan101.mika.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchChatRevealTest {
    @Test fun revealsShortRepliesByCodePoint() {
        val target = "你🙂好"
        assertEquals("你", advanceResearchText("", target))
        assertEquals("你🙂", advanceResearchText("你", target))
        assertEquals(target, advanceResearchText("你🙂", target))
    }

    @Test fun resetsWhenTheToolPhaseReplacesProvisionalText() {
        assertEquals("", advanceResearchText("我先查一下", ""))
        assertEquals("找", advanceResearchText("预计", "找到两条资料"))
    }

    @Test fun catchesUpWhenAProviderReturnsTheWholeReplyAtOnce() {
        val target = "结果".repeat(1_000)
        var shown = ""
        repeat(350) { shown = advanceResearchText(shown, target) }
        assertEquals(target, shown)
        assertTrue(advanceResearchText("", target).length > 1)
    }
}
