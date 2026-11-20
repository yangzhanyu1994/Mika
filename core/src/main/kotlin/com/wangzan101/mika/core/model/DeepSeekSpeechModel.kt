package com.wangzan101.mika.core.model

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DeepSeek streams raw PCM on the official text-to-speech endpoint. */
class DeepSeekSpeechModel(
    private val apiKey: String?,
    maxChars: Int,
    private val http: OkHttpClient,
) : TtsModel {
    override val name = "DeepSeek TTS"
    override val capabilities = TtsCapabilities(setOf("stella", "tide"), minOf(maxChars, 10_000), setOf("wav"))

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val key = apiKey?.takeIf(String::isNotBlank) ?: throw ModelError.Auth()
        val text = request.text.trim()
        val voice = request.voice?.ifBlank { null } ?: DEFAULT_VOICE
        if (text.isEmpty() || text.length > capabilities.maxChars) throw TtsError.Unsupported("Invalid text length")
        if (voice !in checkNotNull(capabilities.voices)) throw TtsError.Unsupported("Invalid DeepSeek voice")
        if (request.format != "wav") throw TtsError.Unsupported("DeepSeek playback requires WAV")
        val payload = buildJsonObject {
            put("text", text)
            put("voice_id", voice)
            put("format", "pcm")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE_URL/v1/tools/tts")
            .headerOrReject("Authorization", "Bearer $key")
            .post(payload).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(HttpSupport.networkError(http, req, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(::readAudio) }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }) { e ->
                        continuation.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                    }
                }
            })
        }
    }

    private fun readAudio(response: Response): TtsResponse {
        if (!response.isSuccessful) throw ModelHttp.openAiError(response.code, response.header("Retry-After"), "", name)
        val body = response.body ?: throw TtsError.BadAudio("No DeepSeek audio returned")
        if (body.contentType()?.let { it.type == "audio" && it.subtype == "pcm" } != true) {
            throw TtsError.BadAudio("Invalid DeepSeek audio type")
        }
        val source = body.source()
        if (source.request(MAX_TTS_BYTES - 43L)) throw TtsError.TooLarge(MAX_TTS_BYTES + 1L)
        val pcm = source.readByteArray()
        if (pcm.isEmpty() || pcm.size % 2 != 0) throw TtsError.BadAudio("Incomplete DeepSeek audio")
        return TtsResponse(wrapPcm16Wav(pcm, RATE), "audio/wav", RATE)
    }

    companion object {
        const val BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_VOICE = "stella"
        private const val RATE = 24_000
    }
}
