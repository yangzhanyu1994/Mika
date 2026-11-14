package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.memory.finishReply
import com.wangzan101.mika.core.memory.memStore
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopMemoryTest {
    private val manual = MemorySource("manual", atMs = 1)
    private fun Message.text() = plainText()

    private fun store() = memStore().apply {
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), manual).getOrThrow()
        add(MemoryKind.fact, "用户的公司在张江", listOf("公司"), manual).getOrThrow()
    }

    @Test fun blockInsertedBeforeTaskAfterContext() {
        val s = store()
        val model = FakeModel(finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("帮我点个外卖", listOf(Turn("查一下天气", "晴", "succeeded")))
        }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith(Conversation.HEADER))
        assertTrue(msgs[1].startsWith("Memories about the user"))
        // 记忆总量装得下就全量注入:与本次任务词面无关的「公司在张江」也在(以前会被 BM25 检索丢掉)
        assertTrue(msgs[1].contains("用户点外卖不要香菜"))
        assertTrue(msgs[1].contains("张江"))
        assertEquals("帮我点个外卖", msgs[2])
    }

    @Test fun unrelatedMemoryStillInjectedInFullMode() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
        runBlocking { loop.run("看看明天天气") }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith("Memories about the user"))
        assertEquals("看看明天天气", msgs[1])
        assertEquals(s.items.value.map { it.id }, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
    }

    @Test fun usedIdsRecordedAndMarked() {
        val s = store()
        val events = mutableListOf<RunEvent>()
        val loop = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = s)
        runBlocking { loop.run("帮我点个外卖") }
        val ids = s.items.value.map { it.id }
        assertEquals(ids, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
        assertEquals(listOf(ids), events.filterIsInstance<RunEvent.MemoryUsed>().map { it.ids })
    }

    // 浏览器页上问:页面说明里的标题、网址、选中的字是网页上的,不算用户说的话;记忆的引文出自它们时不收
    @Test fun pageDescriptionIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理", "默认地址 幸福路 8 号")
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
                .run(AttachedPage.compose(page, "这一页的地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这一页的地址对不对"), said.all)
    }

    // 浏览器页上插话带着新选中的字:只收插话里打的字,页面说明不收
    @Test fun pageSteerAddsOnlyTheTypedWords() = runBlocking {
        assertEquals(listOf("这一页的地址对不对", "改成这个"), saidWithPageSteer("改成这个"))
    }

    // 插话只选了字、没打字:这一句什么也不收
    @Test fun selectionOnlySteerAddsNothing() = runBlocking {
        assertEquals(listOf("这一页的地址对不对"), saidWithPageSteer(""))
    }

    // 浏览器页上回答追问也一样:回答带着页面说明和选中的字时只收打的字
    @Test fun pageAnswerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val ask = ModelReply(listOf(Part.ToolCall("q1", "ask_user", """{"prompt":"用哪个地址?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(ask, finishReply, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
        val run = async { loop.run(AttachedPage.compose(page, "这一页的地址对不对")) }
        withTimeout(5_000) { loop.state.first { it is TaskState.WaitingInput } }
        assertTrue(loop.answerInput(AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), "就这个")))
        run.await()
        assertEquals(listOf("这一页的地址对不对", "就这个"), said.all)
    }

    /** 在浏览器页上开局,插话带着新选中的字和 [words];返回这次记下的用户的话。 */
    private suspend fun saidWithPageSteer(words: String): List<String> {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), words))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
            .run(RunSpec(RunId("r1"), AttachedPage.compose(page, "这一页的地址对不对"), "thread-1"), inbox)
        return said.all
    }

    // 引了 Mika 的回答:引的那段是 Mika 说的,记忆的引文不认,只收用户打的字
    @Test fun quotedReplyIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, MIKA_ADDRESS), "这个地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这个地址对不对"), said.all)
    }

    // 引了自己说过的话:对话页引的是他当时打的字,连那段一起收,记忆的引文照样认
    @Test fun ownQuoteIsTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户家的猫叫团子","quote":"我家猫叫团子"}""")), StopReason.ToolUse, null)
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子"), "这个记一下"))
        }
        assertTrue(s.items.value.any { it.content == "用户家的猫叫团子" })
        assertEquals(listOf("我家猫叫团子\n这个记一下"), said.all)
    }

    // 带了附件:附件那几行是 App 写的路径和大小,不收
    @Test fun attachmentLinesAreNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        runBlocking {
            AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(AttachmentLines.compose("总结一下", listOf("/workspace/inbox/Quarterly_Financial_Report_Final_v2.pdf" to "2 KB")))
        }
        assertEquals(listOf("总结一下"), said.all)
    }

    // 插话引了 Mika 的回答:只收插话里打的字
    @Test fun quotedSteerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, MIKA_ADDRESS), "换成这个"))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run(RunSpec(RunId("r1"), "帮我下单", "thread-1"), inbox)
        assertEquals(listOf("帮我下单", "换成这个"), said.all)
    }

    // 回答追问时引了 Mika 的回答:也只收打的字
    @Test fun quotedAnswerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val ask = ModelReply(listOf(Part.ToolCall("q1", "ask_user", """{"prompt":"送到哪个地址?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(ask, finishReply, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
        val run = async { loop.run("帮我下单") }
        withTimeout(5_000) { loop.state.first { it is TaskState.WaitingInput } }
        assertTrue(loop.answerInput(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, MIKA_ADDRESS), "就这个")))
        run.await()
        assertEquals(listOf("帮我下单", "就这个"), said.all)
    }

    @Test fun previousConfirmationToAdjacentSuggestionCanBeQuoted() {
        val s = memStore()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
        val prior = listOf(
            Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
        )
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("你可以给你自己加一下", prior)
        }
        assertEquals(listOf("用户希望助手的中文名是知微"), s.items.value.map { it.content })
        assertEquals("嗯，好的，知微", s.items.value.single().source.quote)
    }

    @Test fun olderConfirmationAndAssistantWordsAreNotQuotes() {
        val prior = listOf(
            Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
            Turn("先聊点别的", "好的", "succeeded"),
        )
        for (quote in listOf("嗯，好的，知微", "我建议叫我「知微」")) {
            val s = memStore()
            val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
                """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"$quote"}""")), StopReason.ToolUse, null)
            runBlocking {
                AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                    .run("你可以给你自己加一下", prior)
            }
            assertTrue(quote, s.items.value.isEmpty())
        }
    }

    @Test fun confirmationWithoutSuggestionOrWithForgedTextRejected() {
        val base = listOf(
            Turn("今天天气如何", "今天天气晴", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
        )
        val cases = listOf(
            base to "嗯，好的，知微",
            listOf(
                Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
                Turn("嗯，好的，知微", "好的", "succeeded"),
            ) to "嗯，好的，知微，顺便记住我家住张江路100号",
        )
        for ((prior, quote) in cases) {
            val s = memStore()
            val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
                """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"$quote"}""")), StopReason.ToolUse, null)
            runBlocking {
                AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                    .run("你可以给你自己加一下", prior)
            }
            assertTrue(quote, s.items.value.isEmpty())
        }
    }

    @Test fun previousConversationQuoteIsCleared() = runBlocking {
        val s = memStore()
        val said = UserUtterances()
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run("嗯，好的，知微", listOf(Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded")))
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
        AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run("你可以给你自己加一下")
        assertTrue(s.items.value.isEmpty())
        assertEquals(listOf("你可以给你自己加一下"), said.all)
    }

    @Test fun disabledMeansNoBlockNoTools() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = null)
        runBlocking { loop.run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), model.requests.first().withoutRuntime().map { it.text() })
        assertFalse(model.offeredTools.first().any { it.startsWith("memory_") })
        assertTrue(loop.usedMemoryIds.isEmpty())
    }

    private companion object {
        /** 用户引的 Mika 之前的一句回答。 */
        const val MIKA_ADDRESS = "你常用的收货地址是幸福路 8 号"
    }

    // 从通知里得来的那一段带进任务:在用户的话之前,套着自己的外壳;不给就没有
    @Test fun observedContextRidesIntoTheTaskSeparately() {
        val model = FakeModel(finishReply)
        runBlocking { AgentLoop(model, toolDispatcher(FakeScreen()), "B", observed = "Today so far:\n- 王老师通知明天开会").run("帮我点个外卖") }
        val first = model.requests.first().withoutRuntime()
        assertEquals(listOf(ContextKind.Observed, null), first.map { ContextBuilder.kindOf(it) })
        assertEquals(listOf("Today so far:\n- 王老师通知明天开会", "帮我点个外卖"), first.map { it.text() })
        val plain = FakeModel(finishReply)
        runBlocking { AgentLoop(plain, toolDispatcher(FakeScreen()), "B").run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), plain.requests.first().withoutRuntime().map { it.text() })
    }

    // 任务带着「从通知里得来的」那一段时,「上一轮确认」的例外不开:上一轮的建议可能就是照着那一段说的
    @Test fun previousConfirmationIsNotUsedWhenObservedContextIsCarried() {
        val prior = listOf(
            Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
        )
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
        val s = memStore()
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s,
                observed = "Today so far:\n- x").run("你可以给你自己加一下", prior)
        }
        assertTrue(s.items.value.isEmpty())
    }

    // 带了那一段就在运行记录里记一笔(只有长度,没有内容),任务自己也说得出带没带;没带的不记
    @Test fun carryingObservedContextIsRecordedWithoutItsText() {
        fun run(observed: String?): Pair<AgentLoop, List<RunEvent>> {
            val events = mutableListOf<RunEvent>()
            val agent = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen()), "B", observed = observed,
                sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            runBlocking { agent.run("帮我点个外卖") }
            return agent to events
        }
        val text = "Today so far:\n- 王老师通知明天开会"
        val (carried, events) = run(text)
        assertTrue(carried.carriedObserved)
        val mark = events.filterIsInstance<RunEvent.State>().single { it.name == ObservedContext.RUN_STATE }
        assertEquals(text.length.toString(), mark.detail)
        assertFalse("王老师" in RunEvent.encode(mark))
        val (plain, none) = run(null)
        assertFalse(plain.carriedObserved)
        assertTrue(none.filterIsInstance<RunEvent.State>().none { it.name == ObservedContext.RUN_STATE })
    }
}

class MemoryBlockCompressionTest {
    @Test fun memoryBlockSurvivesAggressiveCompression() {
        val block = Message(Role.User, listOf(Part.Text("Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):\n1. 用户点外卖不要香菜")), Author.Mika)
        val history = mutableListOf(block, Message(Role.User, listOf(Part.Text("帮我点个外卖")), Author.Mika))
        repeat(30) { i ->
            history += Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_observe", "{}")), Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult("c$i", "{\"ok\":true,\"x\":\"" + "很".repeat(500) + "\"}", false)), Author.Mika)
        }
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 2_000, aggressive = true)
        assertTrue(out.foldedSteps > 0)
        assertEquals(block, out.messages.first())
    }

    // 对话里有通知提醒(第三方文字)时,「上一轮确认」这个例外不开:提醒本身、或转述着提醒的回答,都不能靠用户一句「好的」进记忆
    @Test fun previousConfirmationIsNotUsedInAConversationWithNotices() {
        val suggestion = "我建议叫我「知微」，你觉得怎么样?"
        val notice = Turn("", "1. 微信 张三:$suggestion", "succeeded", runId = NoticeTurn.RUN_PREFIX + "n1")
        val cases = listOf(
            // 提醒那一轮就是被「确认」的那个
            listOf(notice, Turn("嗯，好的，知微", "好的", "succeeded")),
            // 提醒在更早:助手后来的建议可能就是照着通知说的
            listOf(notice, Turn("给我起个中文名", suggestion, "succeeded"), Turn("嗯，好的，知微", "好的", "succeeded")),
        )
        for (prior in cases) {
            val s = memStore()
            val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
                """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
            runBlocking {
                AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                    .run("你可以给你自己加一下", prior)
            }
            assertTrue(prior.size.toString(), s.items.value.isEmpty())
        }
    }
}
