package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ModelBestSpeechModelTest {
    @Test fun combinesAudioInOrderAndUsesTheOfficialRequestShape() = runTest {
        MockWebServer().use { server ->
            val wav = wrapPcm16Wav(byteArrayOf(1, 2, 3, 4), 24_000)
            server.enqueue(sse(chunk(wav.copyOfRange(0, 13)) + chunk(wav.copyOfRange(13, wav.size)) + DONE))

            val response = model(server).synthesize(TtsRequest(" 你好 ", speed = 1.2f))

            assertArrayEquals(wav, response.bytes)
            assertEquals("audio/wav", response.mime)
            val request = server.takeRequest()
            assertEquals("/v1/audio/speech", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals("text/event-stream", request.getHeader("Accept"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("VoxCPM2"), body["model"])
            assertEquals(JsonPrimitive("你好"), body["input"])
            assertEquals(JsonPrimitive(true), body["stream"])
            assertEquals(JsonPrimitive("wav"), body["response_format"])
            assertFalse("speed" in body)
            assertFalse("voice" in body)
        }
    }

    @Test fun rejectsATruncatedStreamEvenIfItContainsWavBytes() = runTest {
        MockWebServer().use { server ->
            server.enqueue(sse(chunk(wrapPcm16Wav(byteArrayOf(1, 2), 24_000))))
            val failure = runCatching { model(server).synthesize(TtsRequest("hello", voice = "default")) }.exceptionOrNull()
            assertTrue(failure is ModelError.StreamCut)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("default"), body["voice"])
        }
    }

    @Test fun rejectsProviderErrorsAndMalformedAudio() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
            server.enqueue(sse("data: {\"type\":\"speech.audio.delta\",\"audio\":\"!\"}\n\n" + DONE))
            assertTrue(runCatching { model(server).synthesize(TtsRequest("hello")) }.exceptionOrNull() is ModelError.Auth)
            assertTrue(runCatching { model(server).synthesize(TtsRequest("hello")) }.exceptionOrNull() is TtsError.BadAudio)
        }
    }

    private fun model(server: MockWebServer) =
        ModelBestSpeechModel(server.url("/v1").toString(), "test-key", "VoxCPM2", 100, OkHttpClient())

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun chunk(bytes: ByteArray): String =
        "event: speech.audio.delta\ndata: {\"type\":\"speech.audio.delta\",\"audio\":\"${Base64.getEncoder().encodeToString(bytes)}\"}\n\n"

    private companion object {
        const val DONE = "event: speech.audio.done\ndata: {\"type\":\"speech.audio.done\"}\n\n"
    }
}
