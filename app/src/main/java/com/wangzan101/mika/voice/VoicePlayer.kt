package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Mika 此刻的朗读状态。 */
sealed interface VoicePlayback {
    data object Idle : VoicePlayback
    data object Loading : VoicePlayback
    data object Playing : VoicePlayback
    data class Error(val message: String) : VoicePlayback
}

/**
 * 本机朗读:Mika 的 TTS 音频先写缓存文件,再用 MediaPlayer 播;同一时间只播一条,新的替换旧的。
 * 申请系统音频焦点(语音内容、短暂独占),来电或别的 App 出声时暂停/降低,播放结束、出错或被停下来时释放。
 */
class VoicePlayer(private val context: Context) {
    private val _state = MutableStateFlow<VoicePlayback>(VoicePlayback.Idle)
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()

    private var generation = 0L
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 播一段合成好的音频;格式从 [mime] 猜扩展名,MediaPlayer 按文件内容解码。 */
    suspend fun play(bytes: ByteArray, mime: String) = withContext(Dispatchers.Main.immediate) {
        // Claim playback before the file write suspends. A newer request invalidates both
        // this write and its eventual MediaPlayer callbacks, even across different screens.
        active?.release(VoicePlayback.Idle)
        active = this@VoicePlayer
        val request = ++generation
        _state.value = VoicePlayback.Loading
        val directory = File(context.cacheDir, "voice")
        val next = File(directory, "tts-${UUID.randomUUID()}.${extensionOf(mime)}")
        try {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                next.writeBytes(bytes)
            }
            if (!owns(request)) return@withContext
            file = next
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attributes)
            mp.setDataSource(next.absolutePath)
            mp.setOnPreparedListener {
                if (owns(request) && player === it) {
                    if (requestFocus(request, it)) {
                        try {
                            it.start()
                            _state.value = VoicePlayback.Playing
                        } catch (_: IllegalStateException) {
                            release(VoicePlayback.Error("cannot start audio"))
                        }
                    } else {
                        release(VoicePlayback.Error("audio focus is not available"))
                    }
                }
            }
            mp.setOnCompletionListener { if (owns(request) && player === it) release(VoicePlayback.Idle) }
            mp.setOnErrorListener { failed, what, extra ->
                if (owns(request) && player === failed) release(VoicePlayback.Error("player error $what/$extra"))
                true
            }
            mp.prepareAsync()
        } catch (error: CancellationException) {
            if (owns(request)) release(VoicePlayback.Idle)
            throw error
        } catch (error: Exception) {
            if (owns(request)) release(VoicePlayback.Error(error.message ?: "cannot play audio"))
        } finally {
            if (file !== next) next.delete()
        }
    }

    /** 停下来并释放。 */
    @MainThread fun stop() = release(VoicePlayback.Idle)

    @MainThread fun fail(message: String) = release(VoicePlayback.Error(message))

    private fun owns(request: Long): Boolean = active === this && generation == request

    private fun requestFocus(request: Long, mp: MediaPlayer): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (!owns(request) || player !== mp) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mp.isPlaying) mp.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mp.setVolume(1f, 1f)
                        if (!mp.isPlaying) runCatching { mp.start() }
                    }
                }
            }
            .build()
        focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @MainThread
    private fun release(state: VoicePlayback) {
        if (active === this) active = null
        val oldPlayer = player
        player = null
        runCatching { oldPlayer?.release() }
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        file?.delete()
        file = null
        _state.value = state
    }

    private companion object {
        // All accesses are on the Android main thread. Each surface keeps its own UI state.
        var active: VoicePlayer? = null
    }

    private fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "ogg", "opus" -> "ogg"
        "aac" -> "aac"
        "flac" -> "flac"
        "wav", "x-wav" -> "wav"
        else -> "wav"
    }
}
