package com.wangzan101.mika.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 录 16k 单声道 16 位 PCM。打开时按 [MicPlan] 逐个试音源、采样率和声道,立体声和更高采样率先收成 16k 单声道。
 * start 后每约 50ms 更新一次 [level](0..1,按每块 RMS 归一化);
 * stop 返回到目前为止的全部 PCM;录满 [maxMs] 自动停止,之后再 stop 仍返回已录的内容。
 * 录音权限由调用方先检查,这里拿不到麦克风就返回 false。stop 会短暂阻塞(等读循环退出),别在主线程调。
 */
class PcmRecorder(
    private val maxMs: Int = 60_000,
    private val streaming: Boolean = false,
    private val context: Context? = null,
) : StreamingRecorder {
    // At most five seconds while a connection is opening. Never silently drop a sample.
    private val audio = Channel<ShortArray>(100)
    override val chunks: ReceiveChannel<ShortArray> = audio
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var pcm = ShortArray(0)
    private var size = 0

    @SuppressLint("MissingPermission") // 权限由调用方在 start 之前检查
    override fun start(): Boolean {
        // 试配置(含等焦点、失败后再试)可能要一小会儿,别占着锁
        val opened = MicOpener.open(context)
        val rec = opened.record ?: return false
        synchronized(lock) {
            if (record != null) {
                MicOpener.release(rec)
                return false
            }
            pcm = ShortArray(Wav.RATE * maxMs / 1000)
            size = 0
            _level.value = 0f
            record = rec
            val chunk = (opened.rate / 20).coerceAtLeast(1) * opened.channelCount
            job = scope.launch { readLoop(rec, chunk, opened.channelCount, opened.rate) }
            return true
        }
    }

    private fun readLoop(rec: AudioRecord, chunk: Int, channels: Int, rate: Int) {
        val resampler = PcmResampler(channels, rate)
        val buf = ShortArray(chunk)
        while (scope.isActive) {
            val n = rec.read(buf, 0, chunk)
            if (n <= 0) {
                synchronized(lock) {
                    if (streaming && record === rec) audio.close(IOException("Audio capture failed"))
                }
                break // 读失败或已被停掉
            }
            val samples = resampler.push(buf, n)
            if (samples.isEmpty()) continue
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s
            val lvl = (sqrt(sum / samples.size) / 8000.0).coerceIn(0.0, 1.0).toFloat()
            val full = synchronized(lock) {
                if (record !== rec) return // 已被 stop/cancel
                // 在锁里、确认还没被停掉之后才写,免得 cancel 刚归零又被这一块的音量盖回去
                _level.value = lvl
                val take = minOf(samples.size, pcm.size - size)
                System.arraycopy(samples, 0, pcm, size, take)
                size += take
                if (streaming && audio.trySend(samples.copyOf(take)).isFailure) {
                    audio.close(IOException("Audio stream buffer exceeded"))
                    return@synchronized true
                }
                size >= pcm.size
            }
            if (full) break
        }
        // 录满自动停:释放麦克风,已录内容留给之后的 stop 取
        synchronized(lock) {
            if (record === rec) {
                release(rec)
                record = null
                _level.value = 0f // 不录了,波形别停在最后一块的音量上
            }
        }
        if (streaming) audio.close()
    }

    /** 停止并返回到目前为止录到的全部 PCM;没在录(含已自动停止)也安全。 */
    override fun stop(): ShortArray {
        val (rec, j) = synchronized(lock) {
            val r = record
            record = null
            if (r != null) release(r)
            r to job.also { job = null }
        }
        // 等读循环退出,保证之后取到的 PCM 不再变
        if (rec != null) runBlocking { j?.join() }
        if (streaming) audio.close()
        return synchronized(lock) {
            val out = pcm.copyOf(size)
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            out
        }
    }

    /** 放弃这次录音,丢掉已录内容;可重复调用。 */
    override fun cancel() {
        synchronized(lock) {
            record?.let { release(it) }
            record = null
            job?.cancel()
            job = null
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            audio.cancel()
        }
    }

    private fun release(rec: AudioRecord) {
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
    }

    companion object {
        /** 上一个识别器刚放开麦克风时,隔这么久再试一次。 */
        const val RETRY_MS = 200L
    }
}
