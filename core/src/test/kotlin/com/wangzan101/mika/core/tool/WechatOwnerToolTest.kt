package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.wechat.WechatOwnerPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class WechatOwnerToolTest {
    @Test fun routesExplicitTextToPortAndReportsItsRealState() = runTest {
        var sent: String? = null
        val port = object : WechatOwnerPort {
            override suspend fun send(text: String) = buildJsonObject {
                sent = text
                put("ok", false)
                put("status", "uncertain")
            }
        }
        val dispatcher = toolDispatcher(FakeScreen(), wechatOwner = port)
        val result = dispatcher.execute(Part.ToolCall("1", "wechat_send_owner", """{"text":"车辆状态"}"""))
        assertEquals("车辆状态", sent)
        assertEquals(JsonPrimitive("uncertain"), result.content["status"])
        assertEquals(JsonPrimitive(false), result.content["ok"])
        sent = null
        dispatcher.execute(Part.ToolCall("2", "wechat_send_owner", """{"text":"","recipient":"other"}"""))
        assertNull(sent)
    }
}
