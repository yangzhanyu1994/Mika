package com.wangzan101.mika.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 一次要试的录音配置。音源:VOICE_RECOGNITION、MIC、CAMCORDER、VOICE_COMMUNICATION、DEFAULT。
 * 采样率:48k、44.1k、16k。每个采样率先单声道再立体声。minBuf ≤ 0 表示这个声道开不了,只记进诊断。
 */
internal data class MicCandidate(
    val source: Int,
    val sourceName: String,
    val rate: Int,
    val channel: Int,
    val channelName: String,
    val minBuf: Int,
)

/** 已经在录的一条,来自 [android.media.AudioManager.getActiveRecordingConfigurations]。 */
internal data class MicActiveLine(val session: Int, val source: Int, val silenced: Boolean)

/** 一次实际尝试的结果,给界面上那段可复制的诊断。 */
internal data class MicTryLine(
    val source: String,
    val sourceId: Int,
    val rate: Int,
    val channel: String,
    val minBuf: Int,
    val state: Int?,
    val recordingState: Int?,
    val error: String?,
    val clientSilenced: Boolean?,
    val peak: Int? = null,
)

internal data class MicReport(
    val windowFocus: Boolean,
    val waitedMs: Long,
    val retried: Boolean,
    val active: List<MicActiveLine>,
    val tries: List<MicTryLine>,
    val chosen: MicCandidate?,
)

/** 要试的配置顺序。minBuf 由调用方算(真机是 AudioRecord.getMinBufferSize),这里不碰麦克风。 */
internal object MicPlan {
    val sources = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
        MediaRecorder.AudioSource.MIC to "MIC",
        MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
        MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
    )
    val rates = listOf(48_000, 44_100, 16_000)
    val channels = listOf(AudioFormat.CHANNEL_IN_MONO to "MONO", AudioFormat.CHANNEL_IN_STEREO to "STEREO")

    fun attempts(minBuffer: (rate: Int, channel: Int) -> Int): List<MicCandidate> {
        val out = ArrayList<MicCandidate>(sources.size * rates.size * channels.size)
        for ((id, name) in sources) {
            for (rate in rates) {
                for ((mask, channelName) in channels) {
                    out += MicCandidate(id, name, rate, mask, channelName, minBuffer(rate, mask))
                }
            }
        }
        return out
    }
}

/**
 * 这组配置能不能留下。[read] 是 AudioRecord.read 的返回值(负数为错误码)。
 * 系统把客户端静音,或要求听到声音时采样全是 0,都不算打开。
 */
internal fun captureVerdict(read: Int, silenced: Boolean?, peak: Int, requireAudible: Boolean): String? = when {
    read < 0 -> "read($read)"
    silenced == true -> "silenced"
    requireAudible && peak <= 0 -> "zeroAmplitude"
    else -> null
}

/** getMinBufferSize 的失败码,写进诊断。 */
internal fun minBufError(code: Int): String = when (code) {
    AudioRecord.ERROR_BAD_VALUE -> "BAD_VALUE($code)"
    AudioRecord.ERROR_INVALID_OPERATION -> "INVALID_OPERATION($code)"
    else -> "minBuf($code)"
}

internal object MicReportFormat {
    fun format(report: MicReport): String = buildString {
        append("windowFocus=").append(report.windowFocus)
        append(" waitedMs=").append(report.waitedMs).append('\n')
        append("fgsMicrophone=not declared\n")
        append("retried=").append(report.retried).append('\n')
        append("legend state 1=INITIALIZED recState 3=RECORDING minBuf -2=BAD_VALUE\n")
        if (report.active.isEmpty()) {
            append("activeRecording=none\n")
        } else {
            append("activeRecording=")
            append(report.active.joinToString("; ") { "session=${it.session} source=${it.source} silenced=${it.silenced}" })
            append('\n')
        }
        for (t in report.tries) {
            append("try source=").append(t.source).append('(').append(t.sourceId).append(')')
            append(" rate=").append(t.rate)
            append(" ch=").append(t.channel)
            append(" minBuf=").append(t.minBuf)
            append(" state=").append(t.state?.toString() ?: "-")
            append(" recState=").append(t.recordingState?.toString() ?: "-")
            append(" err=").append(t.error ?: "none")
            append(" isClientSilenced=").append(t.clientSilenced?.toString() ?: "unknown")
            append(" peak=").append(t.peak?.toString() ?: "-")
            append('\n')
        }
        val chosen = report.chosen
        if (chosen == null) {
            append("chosen=none\n")
        } else {
            append("chosen source=").append(chosen.sourceName).append('(').append(chosen.source).append(')')
            append(" rate=").append(chosen.rate)
            append(" ch=").append(chosen.channelName).append('\n')
        }
    }
}

/**
 * 把采集到的 PCM 收成 16k 单声道。立体声先取平均;采样率是整数比时按比例取点,否则线性插值。
 * 分块送进来和一次送完,结果一样。
 */
internal class PcmResampler(
    private val channels: Int,
    private val inRate: Int,
    private val outRate: Int = Wav.RATE,
) {
    private var carry = ShortArray(0)
    /** [carry] 第一个采样在整段输入里的帧序号。 */
    private var carryStart = 0L
    /** 已经收过的输入帧数。 */
    private var total = 0L
    /** 已经吐出的输出采样数。下一个输出落在第 emitted * inRate / outRate 帧。 */
    private var emitted = 0L

    fun push(interleaved: ShortArray, nShorts: Int): ShortArray {
        if (nShorts <= 0 || channels <= 0 || inRate <= 0 || outRate <= 0) return ShortArray(0)
        val usable = nShorts - nShorts % channels
        val frames = usable / channels
        if (frames == 0) return ShortArray(0)
        val mono = ShortArray(frames)
        var i = 0
        for (f in 0 until frames) {
            if (channels == 1) {
                mono[f] = interleaved[i]
            } else {
                var sum = 0
                for (c in 0 until channels) sum += interleaved[i + c].toInt()
                mono[f] = (sum / channels).toShort()
            }
            i += channels
        }
        val joined = if (carry.isEmpty()) mono else {
            val both = ShortArray(carry.size + mono.size)
            System.arraycopy(carry, 0, both, 0, carry.size)
            System.arraycopy(mono, 0, both, carry.size, mono.size)
            both
        }
        if (carry.isEmpty()) carryStart = total
        total += frames
        val out = ArrayList<Short>()
        while (emitted < Int.MAX_VALUE) {
            val numer = emitted * inRate.toLong()
            val i0 = numer / outRate
            val rem = numer % outRate
            val need = if (rem == 0L) i0 else i0 + 1
            if (need >= total) break
            val s0 = at(joined, i0)
            out += if (rem == 0L) {
                s0.toShort()
            } else {
                val s1 = at(joined, i0 + 1)
                (s0 + (s1 - s0).toDouble() * rem / outRate).toInt().toShort()
            }
            emitted++
        }
        val keepFrom = emitted * inRate.toLong() / outRate
        val drop = (keepFrom - carryStart).toInt().coerceAtLeast(0)
        if (drop >= joined.size) {
            carry = ShortArray(0)
            carryStart = total
        } else {
            carry = joined.copyOfRange(drop, joined.size)
            carryStart += drop
        }
        return ShortArray(out.size) { out[it] }
    }

    private fun at(buf: ShortArray, absolute: Long): Int = buf[(absolute - carryStart).toInt()].toInt()
}
