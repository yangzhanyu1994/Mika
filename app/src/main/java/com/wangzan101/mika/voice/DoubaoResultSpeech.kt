package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Uses the Doubao realtime dialogue service to read an existing task result. */
internal object DoubaoResultSpeech {
    private const val RATE = 24_000
    private const val MAX_AUDIO = 8 * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun speak(context: Context, credentials: DoubaoCredentials, text: String) {
        val file = withContext(Dispatchers.IO) { File.createTempFile("assistant-spoken-", ".wav", context.cacheDir) }
        try {
            withContext(Dispatchers.IO) {
                val pcm = withTimeout(45_000) { synthesize(credentials, text) }
                val samples = ShortArray(pcm.size / 2)
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                file.writeBytes(Wav.encode(samples, RATE))
            }
            withTimeout(180_000) {
                val player = MediaPlayer()
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        player.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        player.setOnCompletionListener {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        player.setOnErrorListener { _, _, _ ->
                            if (continuation.isActive) continuation.resumeWithException(IOException("Result playback failed"))
                            true
                        }
                        player.setOnPreparedListener {
                            if (continuation.isActive) {
                                try {
                                    it.start()
                                } catch (e: IllegalStateException) {
                                    continuation.resumeWithException(e)
                                }
                            }
                        }
                        player.setDataSource(file.absolutePath)
                        player.prepareAsync()
                    }
                } finally {
                    player.release()
                }
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun synthesize(credentials: DoubaoCredentials, text: String): ByteArray {
        val incoming = Channel<ByteString>(64)
        val request = Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v3/realtime/dialogue")
            .header("X-Api-Resource-Id", "volc.speech.dialog")
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .header("X-Api-App-Key", "PlgvMymc7f3tQnJ6") // Public protocol constant.
            .apply {
                if (credentials.apiKey.isNotBlank()) {
                    header("X-Api-Key", credentials.apiKey)
                } else {
                    header("X-Api-App-ID", credentials.appId)
                    header("X-Api-Access-Key", credentials.accessKey)
                }
            }.build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 1024 * 1024 || incoming.trySend(bytes).isFailure) {
                    incoming.close(IOException("Realtime speech response buffer exceeded"))
                    webSocket.cancel()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(IOException("Realtime speech connection closed"))
                webSocket.close(code, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                incoming.close(IOException("Realtime speech connection failed"))
            }
        })
        val session = UUID.randomUUID().toString()
        fun send(event: Int, id: String? = null, body: String = "{}") {
            if (!socket.send(DoubaoResultFrame.encode(event, id, body).toByteString())) {
                throw IOException("Realtime speech send failed")
            }
        }
        suspend fun receive() = DoubaoResultFrame.decode(incoming.receive().toByteArray())
        try {
            send(1)
            if (receive().event != 50) throw IOException("Realtime speech connection was rejected")
            send(100, session, """{"tts":{"audio_config":{"channel":1,"format":"pcm_s16le","sample_rate":24000}},"dialog":{"extra":{"input_mod":"text"}}}""")
            if (receive().event != 150) throw IOException("Realtime speech session was rejected")
            send(501, session, """{"content":"Read the supplied task result."}""")
            send(500, session, buildJsonObject {
                put("start", true)
                put("content", text)
                put("end", false)
            }.toString())
            send(500, session, """{"start":false,"content":"","end":true}""")

            val audio = ByteArrayOutputStream()
            var readingResult = false
            while (true) {
                val frame = receive()
                when (frame.event) {
                    350 -> {
                        val metadata = Json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject
                        readingResult = metadata["tts_type"]?.jsonPrimitive?.contentOrNull == "chat_tts_text"
                    }
                    352 -> if (readingResult) {
                        if (audio.size() + frame.payload.size > MAX_AUDIO) throw IOException("Task audio is too long")
                        audio.write(frame.payload)
                    }
                    359 -> if (readingResult && audio.size() > 0) {
                        if (audio.size() % 2 != 0) throw IOException("Invalid task audio")
                        send(102, session)
                        send(2)
                        return audio.toByteArray()
                    }
                    51, 52, 152, 153 -> throw IOException("Realtime speech ended before the result")
                }
            }
        } finally {
            socket.cancel()
            incoming.cancel()
        }
    }
}

internal data class DoubaoResultFrame(val event: Int, val payload: ByteArray) {
    companion object {
        fun encode(event: Int, session: String?, json: String): ByteArray {
            val id = session?.toByteArray(Charsets.UTF_8)
            val payload = json.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(12 + (id?.let { 4 + it.size } ?: 0) + payload.size).apply {
                put(byteArrayOf(0x11, 0x14, 0x10, 0))
                putInt(event)
                if (id != null) {
                    putInt(id.size)
                    put(id)
                }
                putInt(payload.size)
                put(payload)
            }.array()
        }

        fun decode(bytes: ByteArray): DoubaoResultFrame {
            val packet = ByteBuffer.wrap(bytes)
            if (bytes.size < 12 || packet.get().toInt() != 0x11) throw IOException("Malformed realtime speech frame")
            val type = packet.get().toInt() and 0xff
            val codec = packet.get().toInt() and 0xff
            packet.get()
            if (type ushr 4 == 15) throw IOException("Realtime speech service rejected the request")
            if (type and 4 == 0 || codec and 0x0f != 0) throw IOException("Unsupported realtime speech frame")
            if (type and 3 != 0) packet.int // Sequence number, when present.
            val event = packet.int
            fun field(): ByteArray {
                if (packet.remaining() < 4) throw IOException("Missing realtime speech frame field")
                val size = packet.int
                if (size !in 0..packet.remaining()) throw IOException("Invalid realtime speech frame field")
                return ByteArray(size).also { packet.get(it) }
            }
            if (event != 51) field() // Connection ID for connection events, session ID otherwise.
            val payload = field()
            if (packet.hasRemaining()) throw IOException("Unexpected realtime speech frame data")
            return DoubaoResultFrame(event, payload)
        }
    }
}
