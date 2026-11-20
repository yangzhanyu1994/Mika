package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekSpeechModelTest {
    @Test fun sendsTextAndDefaultVoiceAndWrapsStreamedPcmForPlayback() = runTest {
        MockWebServer().use { server ->
            val pcm = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm")
                .setChunkedBody(Buffer().write(pcm), 2))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("api.deepseek.com", request.url.host)
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            val audio = DeepSeekSpeechModel("key", 2_000, client).synthesize(TtsRequest("你好"))
            assertArrayEquals(wrapPcm16Wav(pcm, 24_000), audio.bytes)
            val request = server.takeRequest()
            assertEquals("/v1/tools/tts", request.path)
            assertEquals("Bearer key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("你好", body.getValue("text").jsonPrimitive.content)
            assertEquals("stella", body.getValue("voice_id").jsonPrimitive.content)
            assertEquals("pcm", body.getValue("format").jsonPrimitive.content)
        }
    }
}
