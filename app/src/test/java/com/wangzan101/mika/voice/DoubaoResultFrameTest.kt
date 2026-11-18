package com.wangzan101.mika.voice

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DoubaoResultFrameTest {
    @Test
    fun encodesSessionEventInNetworkByteOrder() {
        val packet = DoubaoResultFrame.encode(500, "id", "{}")
        assertArrayEquals(
            byteArrayOf(0x11, 0x14, 0x10, 0, 0, 0, 1, -12, 0, 0, 0, 2, 105, 100, 0, 0, 0, 2, 123, 125),
            packet,
        )
    }

    @Test
    fun returnsRawAudioFromServerFrame() {
        val packet = ByteBuffer.allocate(20)
            .putInt(0x11b40000).putInt(352).putInt(2).put(byteArrayOf(105, 100))
            .putInt(2).put(byteArrayOf(3, 4)).array()
        val frame = DoubaoResultFrame.decode(packet)
        assertEquals(352, frame.event)
        assertArrayEquals(byteArrayOf(3, 4), frame.payload)
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedAudioPayload() {
        val packet = ByteBuffer.allocate(16)
            .putInt(0x11b40000).putInt(352).putInt(0).putInt(100).array()
        DoubaoResultFrame.decode(packet)
    }
}
