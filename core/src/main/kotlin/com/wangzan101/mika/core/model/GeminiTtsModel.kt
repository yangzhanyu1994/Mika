package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gemini TTS：generateContent + `responseModalities: ["AUDIO"]` + speechConfig 里的预置音色。
 * 常见返回是 `audio/L16` 原始 PCM，这里按 mime 里的采样率包成 WAV；别的音频格式按文件头认。
 */
class GeminiTtsModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: TtsCapabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) : TtsModel {
    override val name: String get() = model
    private val apiBase = baseUrl.trimEnd('/') + "/v1beta"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val text = request.text.trim()
        if (text.isEmpty()) throw TtsError.Unsupported("text is empty")
        if (text.length > capabilities.maxChars) throw TtsError.Unsupported("text is longer than ${capabilities.maxChars} characters")
        val voice = request.voice ?: capabilities.voices?.firstOrNull() ?: throw TtsError.Unsupported("voice is required")
        capabilities.voices?.let { if (voice !in it) throw TtsError.Unsupported("voice $voice is not supported") }
        if (model in setOf(DEFAULT_MODEL, "gemini-3.8-flash-tts")) return interactionsSpeech(text, voice)
        val payload = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                })
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                    }
                }
            }
        }
        val req = Request.Builder().url("$apiBase/models/$model:generateContent")
            .withHeaders(headers)
            .headerOrReject("x-goog-api-key", apiKey)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
        val response = HttpSupport.execute(http, req)
        if (response.status !in 200..299) {
            throw ModelHttp.geminiError(response.status, response.retryAfter, response.body, model)
        }
        return parse(response.body)
    }

    private suspend fun interactionsSpeech(text: String, voice: String): TtsResponse {
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "user_input")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                })
            }
            putJsonObject("response_format") { put("type", "audio") }
            putJsonObject("generation_config") {
                putJsonArray("speech_config") { add(buildJsonObject { put("voice", voice) }) }
            }
        }
        val req = Request.Builder().url("$apiBase/interactions")
            .withHeaders(headers)
            .headerOrReject("x-goog-api-key", apiKey)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
        val response = HttpSupport.execute(http, req)
        if (response.status !in 200..299) throw ModelHttp.geminiError(response.status, response.retryAfter, response.body, model)
        val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull() as? JsonObject
            ?: throw ModelError.BadResponse("Invalid Gemini speech response")
        val audio = (root["steps"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "model_output" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .filterIsInstance<JsonObject>()
            .lastOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "audio" }
        val data = (audio?.get("data") as? JsonPrimitive)?.contentOrNull
            ?: throw ModelError.BadResponse("No speech audio in Gemini response")
        if (data.length > 4 * ((MAX_TTS_BYTES + 2) / 3)) throw TtsError.TooLarge(MAX_TTS_BYTES + 1L)
        val bytes = decodeBase64Audio(data, null)
        checkTtsSize(bytes)
        if (sniffAudioMime(bytes) != "audio/wav") throw TtsError.BadAudio("Gemini did not return WAV")
        return TtsResponse(bytes, "audio/wav", 24_000)
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-3.8-flash-lite-tts"
        const val DEFAULT_VOICE = "Kore"
    }

    private fun parse(body: String): TtsResponse {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
        ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull?.let {
            throw ModelError.ContentRefused()
        }
        val candidates = root["candidates"] as? JsonArray ?: throw ModelError.BadResponse("missing candidates")
        for (candidateEl in candidates) {
            val candidate = candidateEl as? JsonObject ?: continue
            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
            parts?.forEach { partEl ->
                val inline = (partEl as? JsonObject)?.get("inlineData") as? JsonObject ?: return@forEach
                val data = (inline["data"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val declared = (inline["mimeType"] as? JsonPrimitive)?.contentOrNull
                val bytes = decodeBase64Audio(data, declared)
                checkTtsSize(bytes)
                val pcm = declared?.contains("L16", ignoreCase = true) == true ||
                    declared?.contains("pcm", ignoreCase = true) == true
                return if (pcm) {
                    val rate = pcmRateOf(declared)
                    TtsResponse(wrapPcm16Wav(bytes, rate), "audio/wav", rate)
                } else {
                    val mime = audioMimeOrBad(bytes, declared)
                    TtsResponse(bytes, mime)
                }
            }
            val refused = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
            if (refused) throw ModelError.ContentRefused()
        }
        throw ModelError.BadResponse("no audio in response")
    }
}
