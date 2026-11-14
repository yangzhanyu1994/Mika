package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutgoingCallIntentToolTest {
    @Test fun contactAndScheduledCallUseTheirOwnPortsAndDoNotDialImmediately() = runTest {
        var dialed = false
        var saved: List<String>? = null
        val port = object : PhoneCallPort {
            override suspend fun state() = buildJsonObject { }
            override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?): JsonObject { dialed = true; return state() }
            override suspend fun answer(callId: String) = state()
            override suspend fun hangup(callId: String) = state()
            override suspend fun takeover() = state()
            override suspend fun resolveContact(name: String) = buildJsonObject { put("ok", true); put("name", name); put("number", "13800138000") }
            override suspend fun scheduleDial(number: String, target: String, goal: String, constraints: String, localTime: String, simId: String?): JsonObject {
                saved = listOf(number, target, goal, constraints, localTime, simId.orEmpty())
                return buildJsonObject { put("ok", true) }
            }
        }
        val d = toolDispatcher(FakeScreen(), phoneCalls = port)
        val match = d.execute(Part.ToolCall("1", "phone_call_resolve_contact", """{"name":"小手机"}"""))
        assertEquals(JsonPrimitive("13800138000"), match.content["number"])
        val result = d.execute(Part.ToolCall("2", "phone_call_schedule_dial", """{"number":"13800138000","target":"小手机","goal":"告诉他该起床了","localTime":"2030-01-02 15:00"}"""))
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(listOf("13800138000", "小手机", "告诉他该起床了", "", "2030-01-02 15:00", ""), saved)
        assertFalse(dialed)
        saved = null
        d.execute(Part.ToolCall("3", "phone_call_schedule_dial", """{"number":"13800138000","target":"小手机","localTime":"2030-01-02 15:00"}"""))
        assertNull(saved)
    }
}
