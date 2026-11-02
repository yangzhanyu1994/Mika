package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig

/**
 * 和聊天服务商分开的语音转文字服务。Key 由用户在设置里填写,这里只有地址和默认模型名。
 * 国内能直接访问的是硅基流动和阿里云百炼;另外留一个兼容 OpenAI /audio/transcriptions 的通用入口。
 */
object SpeechServices {
    const val SILICON = "siliconflow"
    const val DASHSCOPE = "dashscope"
    const val COMPAT = "compat"
    const val ID_PREFIX = "speech:"

    val kinds = listOf(SILICON, DASHSCOPE, COMPAT)

    data class Preset(val kind: String, val baseUrl: String, val model: String)

    val silicon = Preset(SILICON, "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall")
    val dashscope = Preset(DASHSCOPE, "https://dashscope.aliyuncs.com/compatible-mode/v1", "paraformer-v2")

    fun preset(kind: String): Preset? = when (kind) {
        SILICON -> silicon
        DASHSCOPE -> dashscope
        else -> null
    }

    fun provider(choice: SpeechChoice.Service): Provider = Provider(
        id = ID_PREFIX + choice.kind,
        name = choice.kind,
        kind = ProviderConfig.Kind.OpenAiCompat,
        baseUrl = choice.baseUrl,
    )

    fun isSpeechProvider(id: String) = id.startsWith(ID_PREFIX)

    fun kindOf(providerId: String) = providerId.removePrefix(ID_PREFIX)

    /** 聊天服务商全是 DeepSeek:它没有语音识别接口,不能拿来转写。 */
    fun deepSeekOnly(providers: List<Provider>): Boolean =
        providers.isNotEmpty() && providers.all { deepSeek(it) }

    fun deepSeek(provider: Provider): Boolean {
        val host = provider.baseUrl.lowercase()
        val name = provider.name.lowercase()
        return "deepseek" in host || "deepseek" in name
    }
}
