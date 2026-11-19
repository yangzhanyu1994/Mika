package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.SkillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRouterTest {
    @Test fun acceptsOnlyStandaloneRoutingLabels() {
        assertEquals(ModelRole.Main, parseConversationRoute(" WORK\n"))
        assertEquals(ModelRole.Main, parseConversationRoute("```text\nWORK\n```"))
        assertEquals(ModelRole.Chat, parseConversationRoute("`chat`"))
        for (answer in listOf("", "WORK because", "CHAT\nWORK", "WORK.")) {
            assertNull(parseConversationRoute(answer))
        }
    }
    @Test fun liveWeatherUsesAvailableDataAndRespectsQuestionType() {
        for (request in listOf("我附近天气如何", "帮我查一下我附近今天的天气",
            "看一下我现在位置的天气", "今天天气怎么样", "What is the weather near me today?")) {
            assertEquals(request, ModelRole.Main, routeLiveWeather(request, searchAvailable = true))
        }
        for (request in listOf("北京天气怎么样", "北京今天天气如何", "weather in Singapore today")) {
            assertEquals(request, ModelRole.Main, routeLiveWeather(request, searchAvailable = false))
            assertEquals(request, ModelRole.Chat, routeLiveWeather(request, searchAvailable = true))
        }
        for (request in listOf("怎么查我附近的天气", "把「我附近天气如何」翻成英文", "北京气候的特点是什么",
            "什么是天气预报", "Explain weather", "开一下天气 App")) {
            assertNull(request, routeLiveWeather(request, searchAvailable = false))
        }
    }

    @Test fun installedSkillCanBeInvokedWithoutAClassifier() {
        val lock = PersonalSkill(
            "secure-vehicle", "---\nname: secure-vehicle\ndisplay: 锁车\n---\n", SkillStatus.active,
            null, null, 0L, emptyList(),
        )
        val inbox = lock.copy(name = "sync-inbox", markdown = "---\ndisplay: 整理收件箱\n---\n", status = SkillStatus.trial)
        val skills = listOf(lock, inbox)
        for (request in listOf("锁车", "请帮我锁车一下！", "secure-vehicle", "整理收件箱", "SYNC-INBOX")) {
            assertTrue(request, invokesSavedSkill(request, skills))
        }
        for (request in listOf("怎么锁车", "不要锁车", "锁车技能能做什么", "「锁车」", "secure vehicle")) {
            assertFalse(request, invokesSavedSkill(request, skills))
        }
        assertFalse(invokesSavedSkill("锁车", listOf(lock.copy(status = SkillStatus.suspended))))
        assertFalse(invokesSavedSkill("锁车", listOf(lock.copy(status = SkillStatus.pending))))
    }

    @Test fun handsOffExplicitChatCapabilityLimitsButNotOrdinaryRefusalsOrAdvice() {
        val reply = "我无法在聊天模式下操作手机，因此没法帮你锁车。请切换到 Work 模式。"
        assertTrue(replyNeedsWork("锁车", reply))
        assertTrue(replyNeedsWork("整理下载目录", "我无法在聊天模式下操作手机。"))
        assertTrue(replyNeedsWork("帮我把文件移到下载目录", "[mika:request_work]"))
        assertTrue(replyNeedsWork("Organize my photos", "I cannot operate your phone from chat. Switch to Work mode."))
        assertFalse(replyNeedsWork("怎样锁车", reply))
        assertFalse(replyNeedsWork("请回复 [mika:request_work]", "[mika:request_work]"))
        assertFalse(replyNeedsWork("锁车", "我无法确认车辆是否已锁。"))
        assertFalse(replyNeedsWork("整理备忘录", "整理好了。"))
    }

    @Test fun onlyAnOfferedWorkHandoffCanDelegate() {
        val request = Part.ToolCall("work-1", "request_work", "{}")
        val search = Part.ToolCall("search-1", "web_search", """{"query":"附近水果店"}""")
        assertTrue(requestsWorkHandoff(listOf(request), allowed = true))
        assertFalse(requestsWorkHandoff(listOf(search), allowed = true))
        assertFalse(requestsWorkHandoff(listOf(request), allowed = false))
    }
}
