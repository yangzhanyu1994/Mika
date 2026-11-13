package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.*
import org.junit.Test

class CallReceiptCloseoutTest {
    @Test fun onlySuccessfulSaveReceiptGetsSpecificFailureMessage() {
        val user = Message(Role.User, listOf(Part.Text("来电时转告")), Author.User)
        val call = Message(Role.Assistant, listOf(Part.ToolCall("x", "phone_call_incoming_task", "{}")), Author.Mika)
        fun result(ok: Boolean) = Message(Role.Tool, listOf(Part.ToolResult("x", """{"ok":$ok}""", false)), Author.Mika)
        val success = StopRules.closeoutFailed(Closeout.Completed, 60, listOf(user, call, result(true)))
        assertTrue(success.reason.contains("曾成功保存"))
        val failed = StopRules.closeoutFailed(Closeout.Completed, 60, listOf(user, call, result(false)))
        assertFalse(failed.reason.contains("曾成功保存"))
        val later = Message(Role.Assistant, listOf(Part.ToolCall("y", "phone_call_manage_tasks", "{}")), Author.Mika)
        assertFalse(StopRules.closeoutFailed(Closeout.Completed, 60, listOf(user, call, result(true), later)).reason.contains("曾成功保存"))
    }
}
