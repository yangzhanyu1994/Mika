package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.Part
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchHandoffTest {
    @Test fun aCapabilityLimitHandsOffOnceWorthOfWording() {
        assertTrue(replyNeedsWork("帮我看看日历", "聊天模式无法操作你的手机，请切换到工作模式"))
        assertTrue(requestsWorkHandoff(listOf(Part.ToolCall("1", "request_work", "{}")), allowed = true))
        assertFalse(requestsWorkHandoff(listOf(Part.ToolCall("1", "request_work", "{}")), allowed = false))
    }

    @Test fun permissionCancelAndSafetyAreNotAReasonToTryAgain() {
        assertFalse(replyNeedsWork("帮我看看日历", "没有日历权限，无法读取"))
        assertFalse(replyNeedsWork("帮我发消息", "用户取消了这次操作"))
        assertFalse(replyNeedsWork("帮我付款", "这个请求被安全策略拒绝"))
        assertFalse(replyNeedsWork("怎么看日历", "聊天模式无法操作你的手机，请切换到工作模式"))
    }
}
