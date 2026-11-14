package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantEndpointTest {
    @Test fun waitsForSpeechAndFinishesAfterSilence() {
        val endpoint = AssistantEndpoint(0)
        endpoint.level(0.3f, 1000)
        assertEquals(AssistantEndpoint.Decision.Wait, endpoint.decision(2999))
        endpoint.partial("你好", 2500)
        assertEquals(AssistantEndpoint.Decision.Wait, endpoint.decision(4499))
        assertEquals(AssistantEndpoint.Decision.Finish, endpoint.decision(4500))
    }

    @Test fun ignoresQuietNoiseAndStopsWithoutSending() {
        val endpoint = AssistantEndpoint(0)
        endpoint.level(0.05f, 1000)
        assertEquals(AssistantEndpoint.Decision.Wait, endpoint.decision(11999))
        assertEquals(AssistantEndpoint.Decision.NoSpeech, endpoint.decision(12000))
    }

    @Test fun limitsLongRecording() {
        val endpoint = AssistantEndpoint(0)
        endpoint.level(0.3f, 59000)
        assertEquals(AssistantEndpoint.Decision.Finish, endpoint.decision(60000))
    }
}
