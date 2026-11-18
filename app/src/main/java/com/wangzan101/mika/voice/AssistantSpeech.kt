package com.wangzan101.mika.voice

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Record one utterance, ending on silence or on an explicit request to finish. */
internal suspend fun assistantSpeech(
    voice: VoiceSource,
    finishRequested: () -> Boolean,
    onSilence: () -> Unit = {},
    onEvent: (SpeechEvent) -> Unit,
) {
    val session = voice.start()
    val endpoint = AssistantEndpoint(SystemClock.elapsedRealtime())
    try {
        coroutineScope {
            val events = Channel<SpeechEvent>(Channel.UNLIMITED)
            val collector = launch {
                try { session.events.collect { events.send(it) } }
                finally { events.close() }
            }
            var releasedAt: Long? = null
            fun release(now: Long) {
                if (releasedAt != null) return
                releasedAt = now
                onEvent(SpeechEvent.Converting)
                session.release()
            }
            try {
                while (true) {
                    val next = withTimeoutOrNull(150) { events.receiveCatching() }
                    if (next?.isClosed == true) break
                    val event = next?.getOrNull()
                    val now = SystemClock.elapsedRealtime()
                    when (event) {
                        is SpeechEvent.Level -> endpoint.level(event.value, now)
                        is SpeechEvent.Partial -> endpoint.partial(event.stable + event.tail, now)
                        // A recognizer's segment may end before the overlay's two-second window.
                        SpeechEvent.EndOfSpeech -> Unit
                        else -> Unit
                    }
                    if (event != null && event != SpeechEvent.EndOfSpeech) onEvent(event)
                    if (event is SpeechEvent.Final || event is SpeechEvent.Failed) break
                    if (finishRequested()) release(now)
                    if (releasedAt == null) {
                        when (endpoint.decision(now)) {
                            AssistantEndpoint.Decision.NoSpeech -> break
                            AssistantEndpoint.Decision.Finish -> {
                                onSilence()
                                release(now)
                            }
                            AssistantEndpoint.Decision.Wait -> Unit
                        }
                    } else if (now - checkNotNull(releasedAt) >= 35_000) break
                }
            } finally {
                collector.cancel()
                events.cancel()
            }
        }
    } finally {
        session.cancel()
    }
}
