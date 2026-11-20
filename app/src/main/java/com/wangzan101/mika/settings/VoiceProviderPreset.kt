package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.DeepSeekSpeechModel
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.ModelBestSpeechModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import okhttp3.OkHttpClient

internal enum class VoiceProviderPreset(
    val label: String,
    val baseUrl: String,
    val kind: ProviderConfig.Kind,
    val protocol: TtsProtocol,
    val modelId: String,
    val voice: String,
    val keyWebsite: String,
) {
    DeepSeek("DeepSeek", DeepSeekSpeechModel.BASE_URL, ProviderConfig.Kind.OpenAiCompat,
        TtsProtocol.DeepSeekTts, "DeepSeek TTS", DeepSeekSpeechModel.DEFAULT_VOICE, "https://platform.deepseek.com/api_keys"),
    ModelBest("ModelBest", ModelBestSpeechModel.BASE_URL, ProviderConfig.Kind.OpenAiCompat,
        TtsProtocol.OpenAiSpeech, ModelBestSpeechModel.DEFAULT_MODEL, "", "https://platform.modelbest.cn/console/docs"),
    Gemini("Gemini", GeminiTtsModel.BASE_URL, ProviderConfig.Kind.Gemini,
        TtsProtocol.GeminiTts, GeminiTtsModel.DEFAULT_MODEL, GeminiTtsModel.DEFAULT_VOICE, "https://aistudio.google.com/apikey");

    fun matches(provider: Provider): Boolean =
        provider.kind == kind && provider.baseUrl.trim().trimEnd('/').let {
            it == baseUrl || this == DeepSeek && it == "$baseUrl/v1"
        }

    fun choice(provider: Provider) = VoiceChoice(provider.id, modelId, protocol, voice)

    fun model(key: String, maxChars: Int, client: OkHttpClient): TtsModel = when (this) {
        DeepSeek -> DeepSeekSpeechModel(key, maxChars, client)
        ModelBest -> ModelBestSpeechModel(baseUrl, key, modelId, maxChars, client)
        Gemini -> GeminiTtsModel(baseUrl, key, modelId, TtsCapabilities(maxChars = maxChars, formats = setOf("wav")), client)
    }

    val storedLabel: String get() = "$label TTS"
}
