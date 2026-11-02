package com.wangzan101.mika.voice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * 界面有没有焦点、当前在前台的 Activity。
 * 三星上,窗口还没拿到焦点就 startRecording,录音能进 RECORDSTATE_RECORDING,但系统会把客户端静音。
 */
object MicFocus {
    @Volatile
    var hasWindowFocus: Boolean = false

    @Volatile
    var activity: WeakReference<Activity>? = null

    /** 焦点还没来时最多等这么久,避免一启动就被静音;等不到也照样试,诊断里会写 windowFocus=false。 */
    const val WAIT_MS = 400L

    fun await(): Pair<Boolean, Long> {
        val start = SystemClock.elapsedRealtime()
        while (!hasWindowFocus && SystemClock.elapsedRealtime() - start < WAIT_MS) {
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                break
            }
        }
        return hasWindowFocus to (SystemClock.elapsedRealtime() - start)
    }

    /** 安卓 12 起把录音算到前台 Activity 上;没有 Activity 就退回调用方给的 Context。 */
    fun attribution(fallback: Context?): Context? = activity?.get() ?: fallback
}

/**
 * 最近一次打开麦克风的诊断,给「麦克风还是用不了」那张说明复制。
 * [preferred] 是上次真正录上、且没被系统静音的配置,下次先试它。
 */
object MicDiagnostics {
    @Volatile
    var text: String = ""
        internal set

    /** 有一组配置真正开始录、而且 isClientSilenced 不是 true。 */
    @Volatile
    var opened: Boolean = false
        internal set

    /**
     * 手机自带识别录不上,但自己的录音器打开了,只是还没配语音转文字模型。
     * 说明弹层据此改文案,而不是说「还是录不到」。
     */
    @Volatile
    var explainOpened: Boolean = false
        internal set

    @Volatile
    internal var preferred: MicCandidate? = null

    fun clear() {
        text = ""
        opened = false
        explainOpened = false
    }

    internal fun publish(report: MicReport) {
        text = MicReportFormat.format(report)
        opened = report.chosen != null
        if (report.chosen != null) preferred = report.chosen
    }
}

internal class MicOpened(
    val record: AudioRecord?,
    val channelCount: Int,
    val rate: Int,
)

/**
 * 按 [MicPlan] 把音源、采样率、声道逐个试开。
 * 开起来但 [AudioRecordingConfiguration.isClientSilenced] 为 true 的不算成功,放开再试下一组。
 * 全部失败时隔 [PcmRecorder.RETRY_MS] 再走一遍:上一个识别器刚放开麦克风时,第一次经常进不了录音状态。
 */
internal object MicOpener {
    @SuppressLint("MissingPermission")
    fun open(context: Context?): MicOpened {
        val (focus, waited) = MicFocus.await()
        val am = context?.getSystemService(AudioManager::class.java)
        val active = snapshot(am)
        val tries = mutableListOf<MicTryLine>()
        var winner: AudioRecord? = null
        var spec: MicCandidate? = null
        fun walk() {
            for (cand in ordered()) {
                if (winner != null) return
                val result = tryOne(context, am, cand)
                tries += result.line
                if (result.record != null) {
                    winner = result.record
                    spec = cand
                }
            }
        }
        walk()
        var retried = false
        if (winner == null) {
            try {
                Thread.sleep(PcmRecorder.RETRY_MS)
            } catch (_: InterruptedException) {
            }
            retried = true
            walk()
        }
        val chosen = spec
        MicDiagnostics.publish(MicReport(focus, waited, retried, active, tries, chosen))
        val channels = if (chosen?.channel == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        return MicOpened(winner, channels, chosen?.rate ?: Wav.RATE)
    }

    fun release(rec: AudioRecord) {
        try {
            rec.stop()
        } catch (_: IllegalStateException) {
        }
        rec.release()
    }

    private fun ordered(): List<MicCandidate> {
        val all = MicPlan.attempts { rate, channel ->
            AudioRecord.getMinBufferSize(rate, channel, AudioFormat.ENCODING_PCM_16BIT)
        }
        val pref = MicDiagnostics.preferred ?: return all
        val first = all.filter { it.source == pref.source && it.rate == pref.rate && it.channel == pref.channel }
        val rest = all.filterNot { it.source == pref.source && it.rate == pref.rate && it.channel == pref.channel }
        return first + rest
    }

    @SuppressLint("MissingPermission")
    private fun tryOne(context: Context?, am: AudioManager?, cand: MicCandidate): TryResult {
        if (cand.minBuf <= 0) {
            return TryResult(line(cand, null, null, minBufError(cand.minBuf), null), null)
        }
        val rec = try {
            build(context, cand)
        } catch (e: RuntimeException) {
            return TryResult(line(cand, null, null, e.javaClass.simpleName, null), null)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            val state = rec.state
            rec.release()
            return TryResult(line(cand, state, null, "notInitialized", null), null)
        }
        try {
            rec.startRecording()
        } catch (_: IllegalStateException) {
            val state = rec.state
            rec.release()
            return TryResult(line(cand, state, null, "IllegalStateException", null), null)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            val state = rec.state
            val recording = rec.recordingState
            release(rec)
            return TryResult(line(cand, state, recording, "notRecording", null), null)
        }
        val silenced = ourSilenced(am, rec.audioSessionId)
        if (silenced == true) {
            val state = rec.state
            val recording = rec.recordingState
            release(rec)
            return TryResult(line(cand, state, recording, "silenced", true), null)
        }
        return TryResult(line(cand, rec.state, rec.recordingState, null, silenced), rec)
    }

    private fun build(context: Context?, cand: MicCandidate): AudioRecord {
        val channelCount = if (cand.channel == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val frames = (cand.rate / 20).coerceAtLeast(1)
        val bytes = maxOf(cand.minBuf, frames * channelCount * 2 * 4)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(cand.rate)
            .setChannelMask(cand.channel)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioSource(cand.source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
        val attr = MicFocus.attribution(context)
        if (attr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(attr)
        return builder.build()
    }

    private fun ourSilenced(am: AudioManager?, session: Int): Boolean? {
        if (am == null || session == 0) return null
        repeat(4) { i ->
            val mine = configs(am).filter { it.clientAudioSessionId == session }
            if (mine.isNotEmpty()) return mine.any { it.isClientSilenced }
            if (i < 3) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    private fun snapshot(am: AudioManager?): List<MicActiveLine> =
        configs(am).map { MicActiveLine(it.clientAudioSessionId, it.audioSource, it.isClientSilenced) }

    private fun configs(am: AudioManager?): List<AudioRecordingConfiguration> {
        if (am == null) return emptyList()
        return runCatching { am.activeRecordingConfigurations }.getOrDefault(emptyList())
    }

    private fun line(cand: MicCandidate, state: Int?, recording: Int?, error: String?, silenced: Boolean?) =
        MicTryLine(cand.sourceName, cand.source, cand.rate, cand.channelName, cand.minBuf, state, recording, error, silenced)

    private class TryResult(val line: MicTryLine, val record: AudioRecord?)
}
