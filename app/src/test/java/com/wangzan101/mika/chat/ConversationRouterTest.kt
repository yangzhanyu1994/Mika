package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
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
    @Test fun nearbyWeatherNeedsWorkWhilePublicWeatherDoesNot() {
        for (request in listOf("我附近天气如何", "帮我查一下我附近今天的天气", "What is the weather near me today?")) {
            assertTrue(request, isNearbyWeatherRequest(request))
        }
        for (request in listOf("北京今天天气如何", "怎么查我附近的天气", "把「我附近天气如何」翻成英文")) {
            assertFalse(request, isNearbyWeatherRequest(request))
        }
    }

    @Test fun onlyAnOfferedWorkHandoffCanDelegate() {
        val request = Part.ToolCall("work-1", "request_work", "{}")
        val search = Part.ToolCall("search-1", "web_search", """{"query":"附近水果店"}""")
        assertTrue(requestsWorkHandoff(listOf(request), allowed = true))
        assertFalse(requestsWorkHandoff(listOf(search), allowed = true))
        assertFalse(requestsWorkHandoff(listOf(request), allowed = false))
    }
}
