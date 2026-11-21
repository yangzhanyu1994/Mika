package com.wangzan101.mika.calls

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Routes the in-app call between the speaker and the private device.
 * The reported choice is the device the platform is actually using.
 * On API 31 and newer that is setCommunicationDevice. Older releases use the speakerphone switch.
 * Hangup restores the previous mode and clears the communication device.
 */
internal class CallAudioSession(private val audio: AudioManager) {
    private var wantSpeaker = true
    private var started = false
    private val previousMode = audio.mode
    private var previousSpeaker: Boolean? = null
    private var listener: ((CallAudioPolicy.Choice) -> Unit)? = null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun setListener(block: (CallAudioPolicy.Choice) -> Unit) {
        listener = block
    }

    fun start(preferSpeaker: Boolean): CallAudioPolicy.Choice {
        wantSpeaker = preferSpeaker
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        } else {
            previousSpeaker = audio.isSpeakerphoneOn
        }
        started = true
        return apply(wantSpeaker)
    }

    fun toggle(): CallAudioPolicy.Choice {
        wantSpeaker = !current().speaker
        return apply(wantSpeaker)
    }

    fun apply(speaker: Boolean): CallAudioPolicy.Choice {
        wantSpeaker = speaker
        if (Build.VERSION.SDK_INT >= 31) {
            val available = audio.availableCommunicationDevices.map { CallAudioPolicy.Device(it.id, it.type) }
            val choice = CallAudioPolicy.choose(available, speaker)
            val match = audio.availableCommunicationDevices.firstOrNull { it.id == choice.device?.id }
            if (match != null) audio.setCommunicationDevice(match)
            return current()
        }
        audio.isSpeakerphoneOn = speaker
        return current()
    }

    fun current(): CallAudioPolicy.Choice {
        if (Build.VERSION.SDK_INT >= 31) {
            val device = audio.communicationDevice
            val speaker = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            return CallAudioPolicy.Choice(
                device?.let { CallAudioPolicy.Device(it.id, it.type) },
                speaker = speaker,
                fallback = !wantSpeaker && speaker,
            )
        }
        val speaker = audio.isSpeakerphoneOn
        return CallAudioPolicy.Choice(null, speaker = speaker, fallback = !wantSpeaker && speaker)
    }

    fun restore() {
        if (!started) return
        if (Build.VERSION.SDK_INT >= 31) {
            audio.unregisterAudioDeviceCallback(callback)
            audio.clearCommunicationDevice()
        } else if (previousSpeaker != null) {
            audio.isSpeakerphoneOn = previousSpeaker == true
        }
        audio.mode = previousMode
        started = false
    }

    private fun refresh() {
        if (!started) return
        listener?.invoke(apply(wantSpeaker))
    }
}
