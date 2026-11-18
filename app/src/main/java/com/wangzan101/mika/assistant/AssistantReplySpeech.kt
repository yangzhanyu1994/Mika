package com.wangzan101.mika.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Play a single result on the media route. Keep playback separate from cellular call audio. */
internal suspend fun speakAssistantResult(
    context: Context,
    credentials: VolcRealtimeCredentials,
    speaker: String,
    text: String,
): Boolean = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(AudioManager::class.java)
    if (manager.mode != AudioManager.MODE_NORMAL || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
        return@withContext false
    }
    val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    val interrupted = Channel<Unit>(Channel.CONFLATED)
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change -> if (change < 0) interrupted.trySend(Unit) }
        .build()
    if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@withContext false
    try {
        coroutineScope {
            val playback = async {
                withTimeoutOrNull(120_000) { streamSpeech(credentials, speaker, text, attributes) } == true
            }
            try {
                select {
                    playback.onAwait { it }
                    interrupted.onReceive { false }
                }
            } finally {
                playback.cancel()
            }
        }
    } finally {
        manager.abandonAudioFocusRequest(focus)
        interrupted.close()
    }
}

private suspend fun streamSpeech(
    credentials: VolcRealtimeCredentials,
    speaker: String,
    text: String,
    attributes: AudioAttributes,
): Boolean {
    val rate = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val channels = AudioFormat.CHANNEL_OUT_MONO
    val minimum = AudioTrack.getMinBufferSize(rate, channels, encoding)
    if (minimum <= 0) return false
    val track = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setChannelMask(channels).setSampleRate(rate).build())
        .setBufferSizeInBytes(maxOf(minimum, rate / 5))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    try {
        if (track.state != AudioTrack.STATE_INITIALIZED) return false
        return VolcRealtimeClient().connect(credentials, VolcRealtimeConfig(speaker = speaker)).use { session ->
            if (!session.synthesize(text)) return false
            coroutineScope {
                // Read frames promptly even when the provider supplies audio faster than realtime.
                val queue = Channel<ByteArray>(Channel.UNLIMITED)
                val player = async {
                    var written = 0L
                    for (pcm in queue) {
                        if (written == 0L) track.play()
                        var offset = 0
                        while (offset < pcm.size) {
                            val count = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0) { "Could not play realtime speech" }
                            offset += count
                            if (count == 0) delay(10)
                        }
                        written += offset
                    }
                    written
                }
                try {
                    var received = 0L
                    val end = session.events.first { event ->
                        when (event) {
                            is VolcRealtimeEvent.Audio -> {
                                received += event.pcm.size
                                check(received <= rate * 2L * 120) { "Realtime speech exceeds playback window" }
                                queue.send(event.pcm)
                                false
                            }
                            VolcRealtimeEvent.AssistantAudioEnded, VolcRealtimeEvent.AssistantInterrupted,
                            VolcRealtimeEvent.Closed, is VolcRealtimeEvent.Failed -> true
                            else -> false
                        }
                    }
                    if (end != VolcRealtimeEvent.AssistantAudioEnded || received == 0L) return@coroutineScope false
                    queue.close()
                    val frames = player.await() / 2L
                    // Provider completion can arrive while audio is still queued in the local track.
                    withTimeoutOrNull(5_000) {
                        while (Integer.toUnsignedLong(track.playbackHeadPosition) < frames) delay(20)
                        true
                    } == true
                } finally {
                    queue.cancel()
                    player.cancel()
                }
            }
        }
    } finally {
        track.release()
    }
}
