package com.wangzan101.mika.media

import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings

/** Check the current master switch before resolving a provider or synthesizing speech. */
internal suspend fun readAloud(
    text: String,
    settings: VoiceSettings,
    model: (VoiceChoice, TtsCapabilities) -> TtsModel?,
    doubao: (TtsCapabilities) -> TtsModel? = { null },
    preview: Boolean = false,
    play: suspend (TtsResponse) -> Unit,
) {
    val state = settings.state.value
    if (!state.enabled && !preview) return
    val choice = state.choice.takeUnless { state.useDoubao }
    val caps = settings.capabilities() ?: return
    val tts = (if (state.useDoubao) doubao(caps) else choice?.let { model(it, caps) }) ?: return
    val response = tts.synthesize(
        TtsRequest(text.take(caps.maxChars), choice?.voice?.ifBlank { null }, choice?.speed, "wav"),
    )
    play(response)
}
