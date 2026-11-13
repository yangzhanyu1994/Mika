package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class IncomingCallTaskToolTest {
    @Test fun savesExplicitInstructionsAndRejectsInvalidDuration() = runTest {
        var saved: Triple<String, String, Int>? = null
        val port = object : PhoneCallPort {
            override suspend fun state() = buildJsonObject { }
            override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?) = state()
            override suspend fun answer(callId: String) = state()
            override suspend fun hangup(callId: String) = state()
            override suspend fun takeover() = state()
            override suspend fun saveIncomingTask(target: String, say: String, minutes: Int): JsonObject {
                saved = Triple(target, say, minutes)
                return buildJsonObject { put("ok", true) }
            }
        }
        val dispatcher = toolDispatcher(FakeScreen(), phoneCalls = port)
        val result = dispatcher.execute(Part.ToolCall("1", "phone_call_incoming_task",
            """{"target":"快递员","say":"请放西门快递柜","minutes":1440}"""))
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(Triple("快递员", "请放西门快递柜", 1440), saved)
        saved = null
        dispatcher.execute(Part.ToolCall("2", "phone_call_incoming_task",
            """{"target":"快递员","say":"请放西门快递柜","minutes":1441}"""))
        assertNull(saved)
    }
}
