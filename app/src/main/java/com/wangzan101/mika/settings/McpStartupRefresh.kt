package com.wangzan101.mika.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** One in-flight startup probe. A second call while it is running does nothing. */
class McpStartupRefresh(private val store: McpServerStore) {
    private val running = AtomicBoolean(false)

    fun launch(scope: CoroutineScope) {
        if (!store.refreshOnStart()) return
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                store.probeEnabled()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed probe is already recorded per server. Startup continues.
            } finally {
                running.set(false)
            }
        }
    }
}
