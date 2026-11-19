package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PROGRESS_CALL = "task_progress"

internal fun List<Part>.firstActionCall(): Part.ToolCall? =
    filterIsInstance<Part.ToolCall>().firstOrNull { it.name != PROGRESS_CALL }

/** 保留提供方原有的调用顺序和 ID。误写成调用的进度走文字块的校验，实际动作仍只执行一个。 */
internal class ReplyCalls(parts: List<Part>, accept: (CompletionPlanBlock.Extracted) -> Boolean) {
    private val calls = parts.filterIsInstance<Part.ToolCall>()
    val actions = calls.filter { it.name != PROGRESS_CALL }
    val action = actions.firstOrNull()
    val isEmpty = calls.isEmpty()

    private val reports = calls.filter { it.name == PROGRESS_CALL }.associate { call ->
        val ok = accept(CompletionPlanBlock.fromCall(call.argsJson))
        call.id to Part.ToolResult(call.id, buildJsonObject {
            put("ok", ok)
            if (!ok) put("error", "invalid_task_progress")
            put("message", "Report task progress in a <task_progress> JSON block in the reply text.")
        }.toString(), isError = !ok)
    }

    fun results(executed: Part.ToolResult? = null): Message = Message(Role.Tool, resultsExcept(null, executed), Author.Mika)

    fun resultsExcept(excluded: Part.ToolCall?, executed: Part.ToolResult? = null): List<Part.ToolResult> =
        calls.filter { it != excluded }.map { call ->
            if (executed != null && call.id == executed.callId) executed
            else reports[call.id] ?: Part.ToolResult(call.id, buildJsonObject {
                put("ok", false)
                put("error", "parallel_tool_call_skipped")
                put("message", Feedback["parallel_call_skipped"])
            }.toString(), isError = true)
        }
}
