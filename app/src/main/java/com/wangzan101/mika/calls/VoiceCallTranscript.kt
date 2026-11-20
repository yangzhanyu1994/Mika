package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.Turn

/** Collects the text received during an app-to-Mika call for the originating conversation. */
internal class VoiceCallTranscript(private val clock: () -> Long = System::currentTimeMillis) {
    private val turns = mutableListOf<Turn>()
    private val question = StringBuilder()
    private val reply = StringBuilder()
    private var startedAt: Long? = null

    fun accept(event: VolcRealtimeEvent) {
        when (event) {
            is VolcRealtimeEvent.UserTranscript -> {
                if (!event.isFinal || event.text.isBlank()) return
                if (reply.isNotBlank()) flush()
                if (startedAt == null) startedAt = clock()
                if (question.isNotEmpty()) question.append('\n')
                question.append(event.text)
            }
            is VolcRealtimeEvent.AssistantTranscript -> {
                if (startedAt == null) startedAt = clock()
                reply.append(event.delta)
            }
            VolcRealtimeEvent.AssistantTextEnded, VolcRealtimeEvent.AssistantInterrupted -> {
                if (reply.isNotBlank()) flush()
            }
            else -> Unit
        }
    }

    fun finish(): List<Turn> {
        flush()
        return turns.toList()
    }

    private fun flush() {
        if (question.isNotBlank() || reply.isNotBlank()) {
            turns += Turn(question.toString().trim(), reply.toString().trim(), "succeeded", atMs = startedAt)
        }
        question.clear()
        reply.clear()
        startedAt = null
    }
}
