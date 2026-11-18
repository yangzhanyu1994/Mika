package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRouterTest {
    @Test fun onlyAnExplicitWorkDecisionCanStartTheTaskPath() {
        assertEquals(ModelRole.Main, parseConversationRoute(" WORK\n"))
        for (answer in listOf("", "CHAT", "WORK because", "CHAT\nWORK", "WORK.")) {
            assertEquals(ModelRole.Chat, parseConversationRoute(answer))
        }
    }
}
