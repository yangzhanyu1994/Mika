package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
