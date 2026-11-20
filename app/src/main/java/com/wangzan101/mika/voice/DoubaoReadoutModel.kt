package com.wangzan101.mika.voice

import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsError
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Synthesizes the submitted text without opening the microphone. */
internal class DoubaoReadoutModel(
    override val capabilities: TtsCapabilities,
    private val speaker: String,
    private val credentials: suspend () -> VolcRealtimeCredentials?,
) : TtsModel {
    override val name = VolcRealtimeConfig.MODEL

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val text = request.text.trim()
        if (text.isEmpty() || text.length > capabilities.maxChars) throw TtsError.Unsupported("Invalid text length")
        if (request.format != "wav") throw TtsError.Unsupported("Doubao playback requires WAV")
        val auth = credentials() ?: throw ModelError.Auth()
        val rate = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
        val pcm = ByteArrayOutputStream()
        val complete = withTimeoutOrNull(120_000) {
            VolcRealtimeClient().connect(auth, VolcRealtimeConfig(speaker = request.voice ?: speaker)).use { session ->
                if (!session.setMuted(true) || !session.synthesize(text)) throw ModelError.StreamCut()
                session.events.first { event ->
                    when (event) {
                        is VolcRealtimeEvent.Audio -> {
                            val size = pcm.size().toLong() + event.pcm.size
                            if (size > rate * 2L * 120) throw TtsError.TooLarge(size)
                            pcm.write(event.pcm)
                            false
                        }
                        is VolcRealtimeEvent.Failed -> throw event.error
                        VolcRealtimeEvent.AssistantAudioEnded,
                        VolcRealtimeEvent.AssistantInterrupted,
                        VolcRealtimeEvent.Closed -> true
                        else -> false
                    }
                } == VolcRealtimeEvent.AssistantAudioEnded
            }
        }
        if (complete != true || pcm.size() == 0) throw ModelError.StreamCut()
        return TtsResponse(Wav.encode(pcm.toByteArray(), rate), "audio/wav", rate)
    }
}
