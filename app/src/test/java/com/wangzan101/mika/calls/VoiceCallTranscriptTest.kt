package com.wangzan101.mika.calls

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCallTranscriptTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun persistsFinalTextAndInterruptedRepliesInTheOriginatingConversation() {
        val transcript = VoiceCallTranscript { 123L }
        transcript.accept(VolcRealtimeEvent.UserTranscript("猜测", isFinal = false))
        transcript.accept(VolcRealtimeEvent.UserTranscript("你好", isFinal = true))
        transcript.accept(VolcRealtimeEvent.AssistantTranscript("你好，"))
        transcript.accept(VolcRealtimeEvent.AssistantTranscript("我是 Mika"))
        transcript.accept(VolcRealtimeEvent.AssistantInterrupted)
        transcript.accept(VolcRealtimeEvent.UserTranscript("继续", isFinal = true))
        transcript.accept(VolcRealtimeEvent.AssistantTranscript("好的"))
        transcript.accept(VolcRealtimeEvent.AssistantTextEnded)
        transcript.accept(VolcRealtimeEvent.UserTranscript("还在吗", isFinal = true))
        val store = ConversationStore(folder.root)
        store.appendVoiceCall("origin", transcript.finish(), research = true)

        val restored = ConversationStore(folder.root)
        assertEquals(listOf(
            Turn("你好", "你好，我是 Mika", "succeeded", atMs = 123, research = true),
            Turn("继续", "好的", "succeeded", atMs = 123, research = true),
            Turn("还在吗", "", "succeeded", atMs = 123, research = true),
        ), restored.turns("origin"))
        assertEquals(emptyList<Turn>(), restored.turns("another-chat"))
    }
}
