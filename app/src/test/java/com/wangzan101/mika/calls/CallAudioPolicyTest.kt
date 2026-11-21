package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioPolicyTest {
    private val earpiece = CallAudioPolicy.Device(1, CallAudioPolicy.EARPIECE)
    private val speaker = CallAudioPolicy.Device(2, CallAudioPolicy.SPEAKER)
    private val wired = CallAudioPolicy.Device(3, CallAudioPolicy.WIRED_HEADSET)
    private val bluetooth = CallAudioPolicy.Device(7, CallAudioPolicy.BLUETOOTH_SCO)

    @Test fun speakerAndEarpieceFollowTheRequest() {
        val devices = listOf(earpiece, speaker)
        val on = CallAudioPolicy.choose(devices, speaker = true)
        assertEquals(speaker.id, on.device?.id)
        assertTrue(on.speaker)
        assertFalse(on.fallback)
        val off = CallAudioPolicy.choose(devices, speaker = false)
        assertEquals(earpiece.id, off.device?.id)
        assertFalse(off.speaker)
    }

    @Test fun aHeadsetReplacesTheEarpieceAndUnpluggingCanReturn() {
        val plugged = CallAudioPolicy.choose(listOf(earpiece, speaker, wired, bluetooth), speaker = false)
        assertEquals(wired.id, plugged.device?.id)
        val unplugged = CallAudioPolicy.choose(listOf(earpiece, speaker), speaker = false)
        assertEquals(earpiece.id, unplugged.device?.id)
    }

    @Test fun noPrivateRouteStaysOnTheSpeaker() {
        val onlySpeaker = CallAudioPolicy.choose(listOf(speaker), speaker = false)
        assertTrue(onlySpeaker.speaker)
        assertTrue(onlySpeaker.fallback)
        val none = CallAudioPolicy.choose(emptyList(), speaker = true)
        assertEquals(null, none.device)
        assertTrue(none.fallback)
    }
}
