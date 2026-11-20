package com.wangzan101.mika.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.pcmBytes
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground microphone and speaker transport for the same realtime model used by phone calls. */
internal class MikaVoiceCall(private val context: Context, private val phone: CallRuntime) {
    class Failure(val messageId: Int) : Exception()

    suspend fun run(onConnected: () -> Unit, saveTranscript: suspend (List<Turn>) -> Unit) {
        val config = phone.settings.state.value
        val credentials = withContext(Dispatchers.IO) { phone.settings.credentials() }
            ?: throw Failure(R.string.calls_setup_credentials)
        val audio = context.getSystemService(AudioManager::class.java)
            ?: throw Failure(R.string.mika_voice_call_audio_output)
        checkPhoneIdle(audio)
        val session = VolcRealtimeClient().connect(credentials, VolcRealtimeConfig(
            systemPrompt = "You are Mika, speaking with the app user. Reply naturally and concisely in the user's language.",
            speaker = config.speaker,
        ))
        val transcript = VoiceCallTranscript()
        try {
            checkPhoneIdle(audio)
            withTimeoutOrNull(config.maxCallSeconds * 1_000L) {
                audioConversation(audio, session, transcript, onConnected)
            }
        } finally {
            session.close()
            val turns = transcript.finish()
            if (turns.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) { saveTranscript(turns) }
        }
    }

    private suspend fun audioConversation(
        audio: AudioManager, session: VolcRealtimeSession, transcript: VoiceCallTranscript, onConnected: () -> Unit,
    ) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusLost = CompletableDeferred<Unit>()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it < 0) focusLost.complete(Unit) }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw Failure(R.string.mika_voice_call_audio_focus)
        }
        try {
            if (audio.isMicrophoneMute) throw Failure(R.string.mika_voice_call_mic_unavailable)
            val mic = startRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                ?: startRecorder(MediaRecorder.AudioSource.MIC)
                ?: throw Failure(R.string.mika_voice_call_mic_unavailable)
            try {
                val first = withTimeoutOrNull(3_000) { mic.chunks.receiveCatching().getOrNull() }
                if (first == null || first.isEmpty() || mic.isSilenced) {
                    throw Failure(R.string.mika_voice_call_mic_unavailable)
                }
                val speaker = openSpeaker(attributes)
                try {
                    try {
                        speaker.play()
                    } catch (_: IllegalStateException) {
                        throw Failure(R.string.mika_voice_call_audio_output)
                    }
                    sendAudio(session, first)
                    bridge(audio, session, mic, speaker, focusLost, transcript, onConnected)
                } finally {
                    speaker.release()
                }
            } finally {
                mic.cancel()
            }
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun startRecorder(source: Int): PcmRecorder? {
        val mic = PcmRecorder(
            streaming = true, unbounded = true,
            inputSource = source,
            chunkSamples = VolcRealtimeClient.INPUT_CHUNK_BYTES / 2,
        )
        if (mic.start()) return mic
        mic.cancel()
        return null
    }

    private fun sendAudio(session: VolcRealtimeSession, chunk: ShortArray) {
        if (chunk.isNotEmpty() && !session.sendPcm16(pcmBytes(chunk))) {
            throw session.terminalFailure() ?: Failure(R.string.mika_voice_call_connection_lost)
        }
    }

    private suspend fun bridge(
        audio: AudioManager,
        session: VolcRealtimeSession,
        mic: PcmRecorder,
        speaker: AudioTrack,
        focusLost: CompletableDeferred<Unit>,
        transcript: VoiceCallTranscript,
        onConnected: () -> Unit,
    ) = coroutineScope {
        val queue = CallPlaybackQueue()
        val writer = launch {
            val buffer = ByteArray(CallPlaybackQueue.WRITE_BYTES)
            while (true) {
                val block = queue.take(buffer) ?: break
                try {
                    var offset = 0
                    while (offset < block.byteCount && queue.isCurrent(block)) {
                        val written = speaker.write(buffer, offset, block.byteCount - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) throw Failure(R.string.mika_voice_call_audio_output)
                        if (written == 0) delay(5) else offset += written
                    }
                } finally {
                    queue.release(block)
                }
            }
        }
        val recording = launch {
            try {
                for (chunk in mic.chunks) sendAudio(session, chunk)
            } catch (e: VolcRealtimeException) {
                throw e
            } catch (_: IOException) {
                throw Failure(R.string.mika_voice_call_mic_unavailable)
            }
            throw Failure(R.string.mika_voice_call_mic_unavailable)
        }
        val interrupted = launch {
            focusLost.await()
            throw Failure(R.string.mika_voice_call_audio_focus)
        }
        val phoneCall = launch {
            phone.state.first { it.phase != CallPhase.IDLE }
            throw Failure(R.string.calls_auto_busy)
        }
        try {
            checkPhoneIdle(audio)
            onConnected()
            session.events.collect { event ->
                transcript.accept(event)
                when (event) {
                    is VolcRealtimeEvent.Audio -> if (queue.offer(event.pcm) == null) throw Failure(R.string.mika_voice_call_audio_output)
                    VolcRealtimeEvent.AssistantInterrupted -> {
                        queue.invalidate()
                        speaker.pause()
                        speaker.flush()
                        speaker.play()
                    }
                    is VolcRealtimeEvent.Failed -> throw event.error
                    else -> Unit
                }
            }
        } finally {
            recording.cancel()
            phoneCall.cancel()
            interrupted.cancel()
            writer.cancel()
            queue.close()
        }
    }

    private fun checkPhoneIdle(audio: AudioManager) {
        if (phone.isPhoneAudioBusy() || audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_RINGTONE) {
            throw Failure(R.string.calls_auto_busy)
        }
    }

    private fun openSpeaker(attributes: AudioAttributes): AudioTrack {
        val rate = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw Failure(R.string.mika_voice_call_audio_output)
        val track = try {
            AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, CallPlaybackQueue.WRITE_BYTES * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: UnsupportedOperationException) {
            throw Failure(R.string.mika_voice_call_audio_output)
        }
        if (track.state == AudioTrack.STATE_INITIALIZED) return track
        track.release()
        throw Failure(R.string.mika_voice_call_audio_output)
    }
}
