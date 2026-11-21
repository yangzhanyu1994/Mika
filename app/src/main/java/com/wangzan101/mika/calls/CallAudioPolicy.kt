package com.wangzan101.mika.calls

/**
 * Picks the communication device for the in-app voice call.
 * Speaker means the built-in speaker. The other side prefers a wired or Bluetooth
 * headset and otherwise the earpiece. A device with no private route stays on the speaker.
 * These type numbers match AudioDeviceInfo so the choice can be tested without a device.
 */
internal object CallAudioPolicy {
    const val EARPIECE = 1
    const val SPEAKER = 2
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val BLUETOOTH_SCO = 7
    const val USB_DEVICE = 11
    const val USB_HEADSET = 22
    const val BLE_HEADSET = 26

    data class Device(val id: Int, val type: Int)

    data class Choice(val device: Device?, val speaker: Boolean, val fallback: Boolean)

    private val PRIVATE = listOf(WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, USB_DEVICE, BLUETOOTH_SCO, BLE_HEADSET, EARPIECE)

    fun choose(devices: List<Device>, speaker: Boolean): Choice {
        if (speaker) {
            val builtIn = devices.firstOrNull { it.type == SPEAKER }
            return if (builtIn != null) Choice(builtIn, speaker = true, fallback = false)
            else Choice(null, speaker = false, fallback = true)
        }
        val privateDevice = PRIVATE.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
        if (privateDevice != null) return Choice(privateDevice, speaker = false, fallback = false)
        val builtIn = devices.firstOrNull { it.type == SPEAKER }
        return Choice(builtIn, speaker = builtIn != null, fallback = true)
    }
}
