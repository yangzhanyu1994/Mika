package com.wangzan101.mika.core.model

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** One complete text input, with PCM chunks streamed back by the official Doubao TTS API. */
class DoubaoSpeechModel(
    private val apiKey: String?,
    maxChars: Int,
    http: OkHttpClient,
) : TtsModel {
    override val name = RESOURCE
    override val capabilities = TtsCapabilities(maxChars = minOf(maxChars, 4_000), formats = setOf("wav"))
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val key = apiKey?.trim()?.takeIf(String::isNotEmpty) ?: throw ModelError.Auth()
        val text = request.text.trim()
        if (text.isEmpty() || text.length > capabilities.maxChars || request.format != "wav") {
            throw TtsError.Unsupported("Invalid Doubao speech request")
        }
        val payload = buildJsonObject {
            putJsonObject("req_params") {
                put("text", text)
                put("speaker", request.voice?.trim()?.ifEmpty { null } ?: DEFAULT_VOICE)
                putJsonObject("audio_params") {
                    put("format", "pcm")
                    put("sample_rate", RATE)
                }
            }
        }
        val req = Request.Builder().url("$BASE_URL/api/v3/tts/unidirectional")
            .headerOrReject("X-Api-Key", key)
            .header("X-Api-Resource-Id", RESOURCE)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(HttpSupport.networkError(client, req, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(::readAudio) }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }) { e ->
                        continuation.resumeWithException(if (e is IOException) HttpSupport.networkError(client, req, e) else e)
                    }
                }
            })
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readAudio(response: Response): TtsResponse {
        if (!response.isSuccessful) {
            throw ModelHttp.openAiError(response.code, response.header("Retry-After"), "", name)
        }
        val body = response.body ?: throw TtsError.BadAudio("No Doubao audio returned")
        val input = object : FilterInputStream(body.byteStream()) {
            private var remaining = 2L * MAX_TTS_BYTES
            private fun count(bytes: Int): Int {
                if (bytes > 0 && (remaining - bytes).also { remaining = it } < 0) {
                    throw TtsError.TooLarge(2L * MAX_TTS_BYTES + 1)
                }
                return bytes
            }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = count(super.read(bytes, offset, length))
        }
        val pcm = ByteArrayOutputStream()
        try {
            for (packet in Json.decodeToSequence<JsonObject>(input, DecodeSequenceMode.WHITESPACE_SEPARATED)) {
                val status = (packet["code"] as? JsonPrimitive)?.intOrNull
                    ?: throw TtsError.BadAudio("Missing Doubao response code")
                when (status) {
                    20_000_000 -> break
                    45_000_010 -> throw ModelError.Auth()
                    0 -> Unit
                    else -> throw ModelError.BadResponse("Doubao TTS code $status")
                }
                val encoded = (packet["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (encoded.isNotEmpty()) {
                    val remaining = MAX_TTS_BYTES - 44 - pcm.size()
                    if (encoded.length > 4 * ((remaining + 2) / 3)) throw TtsError.TooLarge(MAX_TTS_BYTES + 1L)
                    val bytes = decodeBase64Audio(encoded, null)
                    if (bytes.size > remaining) throw TtsError.TooLarge(MAX_TTS_BYTES + 1L)
                    pcm.write(bytes)
                }
            }
        } catch (_: kotlinx.serialization.SerializationException) {
            throw TtsError.BadAudio("Invalid Doubao audio response")
        }
        if (pcm.size() == 0 || pcm.size() % 2 != 0) throw TtsError.BadAudio("Incomplete Doubao audio")
        return TtsResponse(wrapPcm16Wav(pcm.toByteArray(), RATE), "audio/wav", RATE)
    }

    companion object {
        const val BASE_URL = "https://openspeech.bytedance.com"
        const val RESOURCE = "seed-tts-2.0"
        const val DEFAULT_VOICE = "zh_female_vv_uranus_bigtts"
        private const val RATE = 24_000
    }
}
