package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/** ModelBest's audio/speech endpoint sends one WAV as ordered Base64 chunks in SSE. */
class ModelBestSpeechModel(
    baseUrl: String,
    private val apiKey: String?,
    override val name: String,
    maxChars: Int,
    private val http: OkHttpClient,
) : TtsModel {
    override val capabilities = TtsCapabilities(maxChars = maxChars, formats = setOf("wav"))
    private val endpoint = baseUrl.trimEnd('/') + "/audio/speech"

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val key = apiKey?.takeIf(String::isNotBlank) ?: throw ModelError.Auth()
        val text = request.text.trim()
        if (text.isEmpty()) throw TtsError.Unsupported("text is empty")
        if (text.length > capabilities.maxChars) throw TtsError.Unsupported("text is longer than ${capabilities.maxChars} characters")
        if (!request.format.equals("wav", ignoreCase = true)) throw TtsError.Unsupported("ModelBest only supports WAV")
        val payload = buildJsonObject {
            put("model", name)
            put("input", text)
            request.voice?.trim()?.takeIf(String::isNotEmpty)?.let { put("voice", it) }
            put("response_format", "wav")
            put("stream", true)
        }
        val req = Request.Builder().url(endpoint)
            .headerOrReject("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val audio = ByteArrayOutputStream()
        var complete = false
        val response = HttpSupport.stream(http, req) { data ->
            val event = runCatching { Json.parseToJsonElement(data) }.getOrNull() as? JsonObject
                ?: throw TtsError.BadAudio("ModelBest returned an invalid audio event")
            if (event["error"] != null || event.string("type") == "error") {
                throw ModelError.BadResponse(ModelAccessErrors.detail(data))
            }
            when (event.string("type")) {
                "speech.audio.delta" -> {
                    val encoded = event.string("audio")?.takeIf(String::isNotBlank)
                        ?: throw TtsError.BadAudio("ModelBest returned an empty audio chunk")
                    val remaining = MAX_TTS_BYTES - audio.size()
                    if (encoded.length > 4 * ((remaining + 2) / 3)) throw TtsError.TooLarge(MAX_TTS_BYTES.toLong() + 1)
                    val decoded = decodeBase64Audio(encoded, null)
                    if (decoded.size > remaining) throw TtsError.TooLarge(audio.size().toLong() + decoded.size)
                    audio.write(decoded)
                }
                "speech.audio.done" -> complete = true
            }
            complete
        }
        if (response.status !in 200..299) {
            throw ModelHttp.openAiError(response.status, response.retryAfter, response.body, name)
        }
        if (response.body.isNotBlank()) throw ModelError.BadResponse(ModelAccessErrors.detail(response.body))
        if (!complete) throw ModelError.StreamCut()
        return playableModelBestWav(audio.toByteArray())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val BASE_URL = "https://api.modelbest.cn/v1"
        const val DEFAULT_MODEL = "VoxCPM2"
    }
}
