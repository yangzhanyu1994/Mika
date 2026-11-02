package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 一次按住说话的会话,两种引擎(手机自带识别、服务商转写)对界面长一个样:
 * 界面只收 [events]、在松开时调 [release]、在取消时调 [cancel],不用管背后是哪种引擎。
 */
sealed interface SpeechEvent {
    /** 0..1,画波形。 */
    data class Level(val value: Float) : SpeechEvent

    /** 到此刻听到的全文:[tail] 是末尾还可能变的几个字,其余是 [stable]。 */
    data class Partial(val stable: String, val tail: String) : SpeechEvent

    /** 服务商:松开后在转。 */
    data object Converting : SpeechEvent

    /** 最终文字;空串 = 没听清。 */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val why: SpeechFailure) : SpeechEvent
}

sealed interface SpeechFailure {
    data object NoPermission : SpeechFailure
    data object Busy : SpeechFailure
    data object Network : SpeechFailure
    /**
     * 自己的录音器重试后仍进不了录音状态:麦克风没打开。
     * 可能是别的应用占着,也可能是系统的麦克风开关关着。不是识别服务的客户端错误。
     */
    data object MicUnavailable : SpeechFailure
    /**
     * 识别服务立刻拒绝了这次请求(ERROR_CLIENT)。这是客户端/服务不配合,不是麦克风被占用。
     * 三星国行上常见:自带的是三星语音服务,不给第三方听写,或 Bixby 语音唤醒占着这次会话。
     */
    data object RecognizerRejected : SpeechFailure
    /**
     * 识别服务报录音出错(ERROR_AUDIO),而且自己把音源和采样率试完仍录不上,或录得上但没有语音模型。
     * 试过哪些配置、错误码、有没有被系统静音,在 [com.wangzan101.mika.voice.MicDiagnostics]。
     */
    data object AudioError : SpeechFailure
    data class Provider(val error: ModelError) : SpeechFailure
    data class Unavailable(val why: SpeechEngineSpec.Why) : SpeechFailure
}

interface SpeechSession {
    /** 只给一个收集者;每个事件只送一次,[SpeechEvent.Final]/[SpeechEvent.Failed] 之后或 [cancel] 之后流就结束。 */
    val events: Flow<SpeechEvent>

    /** 能边说边出字(系统、原生流式 ASR 为 true,整段文件转写为 false)。 */
    val live: Boolean

    /** 松开:收尾并给 Final。 */
    fun release()

    /** 丢掉;幂等。 */
    fun cancel()
}

/**
 * 会话的事件出口:无界缓冲,界面晚一点开始收也不丢;结束事件发出后关上。
 * 取消时连同还没被收走的一起丢掉——用户已经不要这次了,迟到的结果不该再冒出来。
 */
internal class SessionEvents {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    @Volatile
    var cancelled = false
        private set

    @Volatile
    var finished = false
        private set

    val flow: Flow<SpeechEvent> = channel.receiveAsFlow().filter { !cancelled }

    fun send(e: SpeechEvent) {
        if (!finished && !cancelled) channel.trySend(e)
    }

    /** 发最后一个事件并关上;已经结束或取消了就什么都不做。返回这次是不是真的发了。 */
    @Synchronized
    fun finish(e: SpeechEvent): Boolean {
        if (finished || cancelled) return false
        finished = true
        channel.trySend(e)
        channel.close()
        return true
    }

    /** 返回这次是不是第一次取消。 */
    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        channel.close()
        return true
    }
}

/** 一开始就用不了的会话(没权限、引擎不可用):只发一个 Failed。 */
internal class FailedSession(why: SpeechFailure) : SpeechSession {
    private val out = SessionEvents().also { it.finish(SpeechEvent.Failed(why)) }
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false
    override fun release() {}
    override fun cancel() { out.cancel() }
}
