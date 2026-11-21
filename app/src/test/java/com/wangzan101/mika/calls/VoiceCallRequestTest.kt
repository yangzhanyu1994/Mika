package com.wangzan101.mika.calls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallRequestTest {
    @Test fun onlyTheLatestCompleteUtteranceCanBeDelegatedOnce() {
        val request = VoiceCallRequest()
        request.acceptFinal(VolcRealtimeEvent.UserTranscript("打开客厅灯。", isFinal = true, isCompleteFinal = false))
        assertNull(request.take(function("打开客厅灯")))
        request.acceptFinal(VolcRealtimeEvent.UserTranscript("打开客厅灯。", isFinal = true))
        assertNull(request.take(function("打开卧室灯")))
        assertEquals("打开客厅灯。", request.take(function("打开客厅灯")))
        assertNull(request.take(function("打开客厅灯")))
        assertTrue(request.remember("unique"))
        assertFalse(request.remember("unique"))

        request.acceptFinal(VolcRealtimeEvent.UserTranscript("打开客厅灯。", isFinal = true))
        request.begin()
        assertNull(request.take(function("打开客厅灯")))
    }

    @Test fun anEmptyArgumentUsesTheFinishedTranscriptNotTheModelText() {
        val request = VoiceCallRequest()
        request.acceptFinal(VolcRealtimeEvent.UserTranscript("今天有什么安排", isFinal = true))
        assertEquals("今天有什么安排", request.take(VolcRealtimeEvent.FunctionCall("id", VolcRealtimeProtocol.MIKA_REQUEST, "{}")))
    }

    @Test fun aDifferentCommandDoesNotRunOrConsumeTheTranscript() {
        val request = VoiceCallRequest()
        request.acceptFinal(VolcRealtimeEvent.UserTranscript("现在几点", isFinal = true))
        assertNull(request.take(function("把余额转出去")))
        assertEquals("现在几点", request.take(function("现在几点")))
    }

    @Test fun assistantPlaybackIsNotANewRequest() {
        val request = VoiceCallRequest()
        request.noteAssistant("好的，灯已经打开了")
        request.acceptFinal(VolcRealtimeEvent.UserTranscript("灯已经打开了", isFinal = true), assistantPlaying = true)
        assertNull(request.take(function("灯已经打开了")))
    }

    @Test fun phoneCallsDoNotAdvertiseMikaTools() {
        val tools = Json.parseToJsonElement(VolcRealtimeProtocol.create("1", VolcRealtimeConfig())).jsonObject
            .getValue("session").jsonObject["tools"]
        assertNull(tools)
    }

    @Test fun onlyAppCallsAdvertiseTheToolAndResultsKeepTheProviderCallId() {
        fun session(config: VolcRealtimeConfig) = Json.parseToJsonElement(VolcRealtimeProtocol.create("1", config))
            .jsonObject.getValue("session").jsonObject

        assertNull(session(VolcRealtimeConfig())["tools"])
        val tool = session(VolcRealtimeConfig(mikaActions = true)).getValue("tools").jsonArray.single().jsonObject
        assertEquals("mika_request", tool.getValue("name").jsonPrimitive.content)
        val frame = Json.parseToJsonElement(VolcRealtimeProtocol.functionResult("2", "provider-id", "Light is on"))
            .jsonObject
        assertEquals("conversation.item.create", frame.getValue("type").jsonPrimitive.content)
        val item = frame.getValue("items").jsonArray.single().jsonObject
        assertEquals("provider-id", item.getValue("call_id").jsonPrimitive.content)
        assertEquals("tool", item.getValue("role").jsonPrimitive.content)
        assertEquals("Light is on", item.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    private fun function(words: String) =
        VolcRealtimeEvent.FunctionCall("id", VolcRealtimeProtocol.MIKA_REQUEST, """{"utterance":"$words"}""")
}
