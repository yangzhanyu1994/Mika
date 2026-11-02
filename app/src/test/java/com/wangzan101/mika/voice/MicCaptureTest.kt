package com.wangzan101.mika.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicCaptureTest {
    @Test
    fun triesRecognitionThenMicThenCommunicationAtThreeRates() {
        val attempts = MicPlan.attempts { _, _ -> 640 }
        assertEquals(18, attempts.size)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, attempts[0].source)
        assertEquals("VOICE_RECOGNITION", attempts[0].sourceName)
        assertEquals(16_000, attempts[0].rate)
        assertEquals("MONO", attempts[0].channelName)
        assertEquals("STEREO", attempts[1].channelName)
        assertEquals(listOf(16_000, 16_000, 48_000, 48_000, 44_100, 44_100), attempts.take(6).map { it.rate })
        assertEquals(MediaRecorder.AudioSource.MIC, attempts[6].source)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, attempts[12].source)
        assertEquals(AudioFormat.CHANNEL_IN_MONO, attempts[0].channel)
        assertEquals(AudioFormat.CHANNEL_IN_STEREO, attempts[1].channel)
    }

    @Test
    fun invalidBufferIsStillListedWithItsErrorCode() {
        val attempts = MicPlan.attempts { rate, channel ->
            if (rate == 16_000 && channel == AudioFormat.CHANNEL_IN_MONO) AudioRecord.ERROR_BAD_VALUE else 2048
        }
        val rejected = attempts.first()
        assertEquals(AudioRecord.ERROR_BAD_VALUE, rejected.minBuf)
        assertEquals("MONO", rejected.channelName)
        assertEquals("BAD_VALUE(${AudioRecord.ERROR_BAD_VALUE})", minBufError(rejected.minBuf))
        assertEquals("INVALID_OPERATION(${AudioRecord.ERROR_INVALID_OPERATION})", minBufError(AudioRecord.ERROR_INVALID_OPERATION))
    }

    @Test
    fun reportNamesSourceRateErrorAndWhetherTheClientWasSilenced() {
        val text = MicReportFormat.format(
            MicReport(
                windowFocus = false,
                waitedMs = 400,
                retried = true,
                active = listOf(MicActiveLine(session = 4, source = 6, silenced = true)),
                tries = listOf(
                    MicTryLine("VOICE_RECOGNITION", 6, 16_000, "MONO", -2, null, null, "BAD_VALUE(-2)", null),
                    MicTryLine("MIC", 1, 48_000, "MONO", 3840, 1, 3, "silenced", true),
                ),
                chosen = null,
            ),
        )
        assertTrue(text.contains("windowFocus=false"))
        assertTrue(text.contains("waitedMs=400"))
        assertTrue(text.contains("fgsMicrophone=not declared"))
        assertTrue(text.contains("retried=true"))
        assertTrue(text.contains("activeRecording=session=4 source=6 silenced=true"))
        assertTrue(text.contains("try source=VOICE_RECOGNITION(6) rate=16000 ch=MONO minBuf=-2 state=- recState=- err=BAD_VALUE(-2) isClientSilenced=unknown"))
        assertTrue(text.contains("try source=MIC(1) rate=48000 ch=MONO minBuf=3840 state=1 recState=3 err=silenced isClientSilenced=true"))
        assertTrue(text.contains("chosen=none"))
    }

    @Test
    fun chosenConfigIsNamedInTheReport() {
        val chosen = MicCandidate(6, "VOICE_RECOGNITION", 48_000, AudioFormat.CHANNEL_IN_MONO, "MONO", 3840)
        val text = MicReportFormat.format(
            MicReport(true, 0, false, emptyList(), emptyList(), chosen),
        )
        assertTrue(text.contains("windowFocus=true"))
        assertTrue(text.contains("activeRecording=none"))
        assertTrue(text.contains("chosen source=VOICE_RECOGNITION(6) rate=48000 ch=MONO"))
    }

    @Test
    fun sixteenKhzMonoIsUnchanged() {
        val input = shortArrayOf(1, -2, 3, 32767, -32768)
        assertArrayEquals(input, PcmResampler(1, 16_000).push(input, input.size))
    }

    @Test
    fun stereoAveragesTheTwoChannels() {
        val input = shortArrayOf(0, 100, 10, 30)
        val out = PcmResampler(2, 16_000).push(input, input.size)
        assertArrayEquals(shortArrayOf(50, 20), out)
    }

    @Test
    fun fortyEightKhzKeepsEveryThirdFrame() {
        val input = ShortArray(9) { it.toShort() }
        assertArrayEquals(shortArrayOf(0, 3, 6), PcmResampler(1, 48_000).push(input, input.size))
    }

    @Test
    fun chunksMatchASinglePush() {
        val input = ShortArray(441) { (it * 17 - 1000).toShort() }
        val one = PcmResampler(1, 44_100).push(input, input.size)
        val split = PcmResampler(1, 44_100)
        val joined = split.push(input, 100) + split.push(input.copyOfRange(100, 250), 150) + split.push(input.copyOfRange(250, 441), 191)
        assertArrayEquals(one, joined)
        assertTrue(one.isNotEmpty())
        val constant = ShortArray(441) { 1000 }
        val flat = PcmResampler(1, 44_100).push(constant, constant.size)
        assertTrue(flat.all { it.toInt() == 1000 })
    }
}
