package com.wangzan101.mika.caution

import com.wangzan101.mika.core.caution.WirelessIdle
import com.wangzan101.mika.settings.CautionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 没有任务时,过一段空闲就关掉无线调试;下次有任务再打开。
 * 写不了系统设置时不会动开关。
 */
class WirelessIdleWatcher(
    private val store: CautionStore,
    private val busy: StateFlow<Boolean>,
    private val canWrite: () -> Boolean,
    private val isOn: () -> Boolean,
    private val turnOn: () -> Boolean,
    private val turnOff: () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun start() {
        scope.launch {
            var idleSince = now()
            var seenTask = false
            while (isActive) {
                val can = canWrite()
                val enabled = WirelessIdle.enabled(store.wirelessIdle, can)
                if (busy.value) {
                    seenTask = true
                    idleSince = now()
                    if (enabled && can && !isOn()) turnOn()
                } else if (seenTask && WirelessIdle.due(enabled, taskRunning = false, idleForMs = now() - idleSince, graceMs = store.graceMs) && can && isOn()) {
                    turnOff()
                }
                delay(5_000)
            }
        }
    }
}
