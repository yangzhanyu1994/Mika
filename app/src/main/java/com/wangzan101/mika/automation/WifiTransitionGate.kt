package com.wangzan101.mika.automation

/** Only confirmed network identities and losses can produce an edge. */
internal class WifiTransitionGate {
    private var initialized = false
    private var current: String? = null

    data class Transition(val disconnected: String?, val connected: String?)

    /** A null observation is a known disconnect; unknown SSID must not be passed here. */
    fun observe(ssid: String?): Transition? {
        if (!initialized) { initialized = true; current = ssid; return null }
        if (current == ssid) return null
        val transition = Transition(disconnected = current, connected = ssid)
        current = ssid
        return transition
    }

    fun reset() { initialized = false; current = null }
}
