package com.wangzan101.mika.core.model

import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DoubaoSpeechModelTest {
    @Test fun speechKeyUsesTtsResourceAndReadsChunkedPcm() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(
                """{"code":0,"data":"AQI="}""" + "\n" +
                    """{"code":0,"data":"AwQ="}""" + "\n" +
                    """{"code":20000000,"data":null}""", 5,
            ))
            val audio = model(server).synthesize(TtsRequest("你好"))
            assertArrayEquals(wrapPcm16Wav(byteArrayOf(1, 2, 3, 4), 24_000), audio.bytes)

            val request = server.takeRequest()
            assertEquals("/api/v3/tts/unidirectional", request.path)
            assertEquals("POST", request.method)
            assertEquals("test-speech-key", request.getHeader("X-Api-Key"))
            assertEquals("seed-tts-2.0", request.getHeader("X-Api-Resource-Id"))
            assertNotNull(UUID.fromString(request.getHeader("X-Api-Request-Id")))
            val parameters = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("req_params").jsonObject
            assertEquals("你好", parameters.getValue("text").jsonPrimitive.content)
            assertEquals("zh_female_vv_uranus_bigtts", parameters.getValue("speaker").jsonPrimitive.content)
            assertEquals("pcm", parameters.getValue("audio_params").jsonObject.getValue("format").jsonPrimitive.content)
        }
    }

    @Test fun authorizationErrorInHttp200RejectsPartialAudio() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(
                """{"code":0,"data":"AQI="}""" + "\n" + """{"code":45000010}""", 5,
            ))
            val error = runCatching { model(server).synthesize(TtsRequest("你好")) }.exceptionOrNull()
            assertEquals(ModelError.Auth::class.java, error?.javaClass)
        }
    }

    private fun model(server: MockWebServer): DoubaoSpeechModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }.build()
        return DoubaoSpeechModel("test-speech-key", 2000, client)
    }
}
