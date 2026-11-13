package com.wangzan101.mika.automation

/** In-memory observation only: process restarts never invent a connection or replay an old edge. */
internal class BluetoothEdgeGate {
    private val states = mutableMapOf<String, Boolean>()
    private val epochs = mutableMapOf<String, Long>()

    data class Pending(val address: String, val connected: Boolean, val epoch: Long, val dueAt: Long)

    /** null means the platform repeated the same connection state; no second trigger. */
    fun observe(address: String, connected: Boolean, now: Long, delayMs: Long): Pending? {
        val key = address.uppercase()
        if (states[key] == connected) return null
        states[key] = connected
        val epoch = (epochs[key] ?: 0L) + 1
        epochs[key] = epoch
        return Pending(key, connected, epoch, now + delayMs.coerceAtLeast(0L))
    }

    /** A reconnect, a later edge, early delivery or disabled Bluetooth cancels the pending trigger. */
    fun isDue(pending: Pending, now: Long, adapterOn: Boolean): Boolean =
        adapterOn && now >= pending.dueAt && states[pending.address] == pending.connected &&
            epochs[pending.address] == pending.epoch

    fun reset() { states.clear(); epochs.clear() }
}
