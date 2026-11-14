package com.wangzan101.mika.voice

/** Detect the end of one assistant utterance. Times use a monotonic clock. */
internal class AssistantEndpoint(private val startedAt: Long) {
    private var lastVoice: Long? = null
    private var textSeen = false

    fun level(value: Float, now: Long) {
        if (value >= 0.12f) lastVoice = now
    }

    fun partial(text: String, now: Long) {
        if (text.isNotBlank()) {
            textSeen = true
            lastVoice = now
        }
    }

    fun decision(now: Long): Decision {
        val last = lastVoice
        if (now - startedAt >= 60_000) return if (last != null || textSeen) Decision.Finish else Decision.NoSpeech
        if (last == null && now - startedAt >= 12_000) return Decision.NoSpeech
        if (last != null && now - last >= 2_000) return Decision.Finish
        return Decision.Wait
    }

    enum class Decision { Wait, Finish, NoSpeech }
}
