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
    @Test fun taskEditsRequireAReadBackAndRejectUnknownOperations() = runTest {
        var mutations = 0
        val port = object : PhoneCallPort {
            override suspend fun state() = buildJsonObject { }
            override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?) = state()
            override suspend fun answer(callId: String) = state()
            override suspend fun hangup(callId: String) = state()
            override suspend fun takeover() = state()
            override suspend fun manageIncomingTask(operation: String, id: String?, expectedSay: String?, say: String?): JsonObject {
                mutations++
                return buildJsonObject { put("ok", true) }
            }
        }
        val d = toolDispatcher(FakeScreen(), phoneCalls = port)
        d.execute(Part.ToolCall("1", "phone_call_manage_tasks", """{"operation":"delete","id":"one"}"""))
        d.execute(Part.ToolCall("2", "phone_call_manage_tasks", """{"operation":"clear"}"""))
        assertEquals(0, mutations)
        d.execute(Part.ToolCall("3", "phone_call_manage_tasks", """{"operation":"edit","id":"one","expected_say":"放门口","say":"放西门"}"""))
        assertEquals(1, mutations)
    }
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
