package com.wangzan101.mika.assistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private val speechHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).readTimeout(20, TimeUnit.SECONDS).build()

/** OpenAI-compatible speech endpoint, returning raw 24 kHz mono PCM16 LE. */
internal fun compatibleSpeech(model: ReadoutModel, key: String, text: String): Flow<ByteArray> = callbackFlow {
    val url = checkNotNull(model.speechUrl())
    val payload = buildJsonObject {
        put("model", model.model)
        put("voice", model.voice)
        put("input", text)
        put("response_format", "pcm")
    }.toString()
    val call = speechHttp.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key")
        .post(payload.toRequestBody("application/json".toMediaType())).build())
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { close(IOException("Speech transport failed")) }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!it.isSuccessful) throw IOException("Speech provider rejected the request")
                    val body = it.body ?: throw IOException("Speech provider returned no audio")
                    val subtype = body.contentType()?.subtype
                    if (subtype != null && subtype !in setOf("octet-stream", "pcm", "raw", "x-pcm")) {
                        throw IOException("Speech provider returned an invalid audio response")
                    }
                    val source = body.source()
                    val buffer = okio.Buffer()
                    while (source.read(buffer, 8192) != -1L) {
                        val size = buffer.size and -2L
                        if (size > 0 && trySendBlocking(buffer.readByteArray(size)).isFailure) return
                    }
                    if (buffer.size != 0L) throw IOException("Speech provider returned a partial audio sample")
                }
                close()
            } catch (_: Exception) {
                close(IOException("Speech audio could not be read"))
            }
        }
    })
    awaitClose { call.cancel() }
}
