package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFixesTest {
    @Test fun explicitBatchPreservesTaskProgressToolResult() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot = ScreenSnapshot(
                "s1", "com.x",
                listOf(
                    UiElement("@e1", "edit", "搜索", 100, 100, listOf(0, 0, 200, 200), null, true, true, false),
                    UiElement("@e2", "button", "确定", 300, 100, listOf(220, 0, 400, 200), null, true, false, false),
                ),
                1080, 2400,
            )
            override suspend fun peek(): ScreenSnapshot = snapshot()
        }
        val model = FakeModel(
            ModelReply(
                listOf(
                    Part.ToolCall("p1", "task_progress", """{"items":[{"id":"a","label":"搜","status":"done"}]}"""),
                    Part.ToolCall("c1", "phone_tap", """{"x":100,"y":100}"""),
                    Part.ToolCall("c2", "phone_back", """{}"""),
                ),
                StopReason.ToolUse, Usage(0, 0),
            ),
            ModelReply(listOf(Part.Text("完成")), StopReason.EndTurn, Usage(0, 0)),
            repeatLast = true,
        )
        val loop = AgentLoop(
            model = model,
            tools = toolDispatcher(screen),
            basePrompt = "test",
            flags = SpeedFlags(batchSteps = true),
            reviewFinalAnswer = false,
        )
        val state = loop.run("点一下再返回")
        assertTrue("Expected Succeeded but got $state", state is TaskState.Succeeded)
        val secondReq = model.requests[1]
        val toolResults = secondReq.filter { it.role == Role.Tool }.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        val ids = toolResults.map { it.callId }.toSet()
        assertEquals(setOf("p1", "c1", "c2"), ids)
    }

    @Test fun appLaunchHandsBackFreshReadRespectsGateAndIntentGate() = runTest {
        var opened = 0
        var gated = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                opened++
                return ActionResult.Ok()
            }
            override suspend fun snapshot(): ScreenSnapshot = ScreenSnapshot(
                "s-open", "com.sankuai.meituan",
                listOf(UiElement("@e1", "button", "外卖", 100, 100, listOf(0, 0, 200, 200), null, true, false, false)),
                1080, 2400,
            )
            override suspend fun peek(): ScreenSnapshot = snapshot()
        }
        val model = FakeModel(
            ModelReply(listOf(Part.Text("已打开美团，看到外卖入口")), StopReason.EndTurn, Usage(0, 0)),
        )
        val loop = AgentLoop(
            model = model,
            tools = toolDispatcher(screen),
            basePrompt = "test",
            gate = { gated++ },
            reviewFinalAnswer = false,
            launchApps = { listOf(AppLaunch.App("com.sankuai.meituan", "美团")) },
        )
        val state = loop.run("打开美团")
        assertTrue(state is TaskState.Succeeded)
        assertEquals(1, opened)
        assertEquals(1, gated)
        val firstReq = model.requests.first()
        val handbackTool = firstReq.filter { it.role == Role.Tool }.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        assertTrue(handbackTool.any { it.callId == "app_launch_read" && "外卖" in it.content })

        // Discuss intent must not eagerly open the app
        var discussOpened = 0
        val discussScreen = object : ScreenPort by FakeScreen() {
            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                discussOpened++
                return ActionResult.Ok()
            }
        }
        val judge = FakeModel(ModelReply(listOf(Part.Text("discuss")), StopReason.EndTurn, Usage(0, 0)))
        val chatModel = FakeModel(ModelReply(listOf(Part.Text("在微信长按两分钟内的消息可以撤回。")), StopReason.EndTurn, Usage(0, 0)))
        val discussLoop = AgentLoop(
            model = chatModel,
            tools = toolDispatcher(discussScreen),
            basePrompt = "test",
            intentJudge = judge,
            reviewFinalAnswer = false,
            launchApps = { listOf(AppLaunch.App("com.tencent.mm", "微信")) },
        )
        val discussState = discussLoop.run("在微信里怎么撤回消息？")
        assertTrue(discussState is TaskState.Succeeded)
        assertEquals(0, discussOpened)
        assertFalse(chatModel.requests.first().any { it.role == Role.Tool })

        // Explicit open command must not block on IntentGate before launching
        val directJudge = FakeModel(ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, Usage(0, 0)))
        val directModel = FakeModel(ModelReply(listOf(Part.Text("已打开奥迪连")), StopReason.EndTurn, Usage(0, 0)))
        var audiOpened = 0
        val audiScreen = object : ScreenPort by FakeScreen() {
            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                audiOpened++
                return ActionResult.Ok()
            }
            override suspend fun snapshot(): ScreenSnapshot = ScreenSnapshot(
                "s-audi", "com.audi.connect",
                listOf(UiElement("@e1", "text", "车辆状态", 100, 100, listOf(0, 0, 200, 200), null, false, false, false)),
                1080, 2400,
            )
            override suspend fun peek(): ScreenSnapshot = snapshot()
        }
        val directLoop = AgentLoop(
            model = directModel,
            tools = toolDispatcher(audiScreen),
            basePrompt = "test",
            intentJudge = directJudge,
            reviewFinalAnswer = false,
            launchApps = { listOf(AppLaunch.App("com.audi.connect", "奥迪连")) },
        )
        val directState = directLoop.run("查看奥迪状态")
        assertTrue(directState is TaskState.Succeeded)
        assertEquals(1, audiOpened)
        assertTrue(directJudge.requests.isEmpty())
    }
}
