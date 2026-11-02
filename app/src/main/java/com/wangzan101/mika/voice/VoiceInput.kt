package com.wangzan101.mika.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore

/** 界面(输入栏、悬浮胶囊)拿语音会话的地方;App 里是 [VoiceInput],界面测试换成假的。 */
interface VoiceSource {
    fun spec(): SpeechEngineSpec
    fun start(language: String? = null): SpeechSession
}

/**
 * 语音输入的入口:对话页输入框和悬浮胶囊只经这里拿会话。
 * 先按用户的选择、手机能力和现有服务商定下引擎([spec]),再开一次会话([start])。
 * [transcriber] 按服务商给出转写器:每家的请求头(客户端标识、它要的会话头)不同。
 */
class VoiceInput(
    context: Context,
    private val settings: SpeechSettings,
    private val providers: ProviderStore,
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, providers.clientHeaders(p)) },
) : VoiceSource {
    private val context = context.applicationContext
    /** 上一次还没丢掉的会话。再开一次之前先取消,避免自己占着麦克风。 */
    private var live: SpeechSession? = null

    /** 界面先问一下能不能用、用的是哪个(卡片顶上那个小标签)。 */
    override fun spec(): SpeechEngineSpec =
        SpeechResolver.resolve(settings.choice.value, SystemSpeech.capability(context, settings.systemSilent.value), providers.providers.value)

    /**
     * 开一次会话,按下时调(要在主线程)。[language] 是识别语言(BCP-47);null 跟 App 界面语言。
     * 没有录音权限、引擎用不了时,给出只发一个 Failed 的会话,界面按同一套处理。
     */
    override fun start(language: String?): SpeechSession {
        MicDiagnostics.clear()
        val spec = spec()
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val tag = language ?: SystemSpeech.speechTag(AppLanguage.current(context))
        live?.cancel()
        val session = when (spec) {
            is SpeechEngineSpec.Doubao -> {
                val credentials = settings.doubaoCredentials()
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured)).also { live = it }
                DoubaoSpeech(PcmRecorder(streaming = true, context = context), { DoubaoTransport().connect(credentials, spec.resourceId) })
            }
            is SpeechEngineSpec.System -> SystemSpeechSession(
                context, spec.onDevice, tag,
                onNoResponse = settings::noteSystemSilent, onAnswered = settings::noteSystemAnswered,
                attempts = SystemSpeech.attempts(SystemSpeech.services(context), settings.systemService),
                captureFallback = captureFallback(tag),
            )
            is SpeechEngineSpec.Provider -> providerSession(spec, tag)
            is SpeechEngineSpec.Unavailable -> FailedSession(SpeechFailure.Unavailable(spec.why))
        }
        live = session
        return session
    }

    /** 自带识别拒绝时,先用设置里的语音服务,否则用已经配好的语音模型。都没有就没有退路。 */
    private fun captureFallback(tag: String): (() -> SpeechSession)? {
        settings.fallbackService()?.let { choice ->
            val spec = SpeechEngineSpec.Provider(SpeechServices.provider(choice), choice.model, TranscribeMethod.AudioTranscriptions)
            return { providerSession(spec, tag) }
        }
        val spec = SpeechResolver.captureFallback(providers.providers.value) ?: return null
        return { providerSession(spec, tag) }
    }

    private fun providerSession(spec: SpeechEngineSpec.Provider, tag: String): SpeechSession {
        val p = spec.provider
        val key = if (SpeechServices.isSpeechProvider(p.id)) settings.serviceKey(SpeechServices.kindOf(p.id))
            else providers.providerKey(p.id)
        val t = transcriber(p)
        val iso = SystemSpeech.isoCode(tag)
        return ProviderSpeech(PcmRecorder(context = context), { wav ->
            t.transcribe(p, key, spec.model, spec.method, wav, iso)
        })
    }
}
