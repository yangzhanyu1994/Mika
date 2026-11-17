package com.wangzan101.mika.task

/** Keeps the obligation until the system confirms the device is locked. */
internal class LocalRelock {
    private var baseline: Long? = null
    @Synchronized fun hasPending() = baseline != null
    @Synchronized fun unlocked(interactions: Long) { if (baseline == null) baseline = interactions }
    @Synchronized fun confirmed() { baseline = null }

    /** After an idle grace period, accept activity that happened earlier, not activity during the wait. */
    @Synchronized fun acceptIdle(start: Long, current: Long): Boolean {
        if (baseline == null || start != current) return false
        baseline = current
        return true
    }

    @Synchronized fun decide(runsRemain: Boolean, locked: Boolean, interactions: Long, canLock: Boolean): Decision {
        val recorded = baseline ?: return Decision.NONE
        if (runsRemain) return Decision.WAIT
        if (locked) { baseline = null; return Decision.NONE }
        return when {
            !canLock -> Decision.WARN
            interactions != recorded -> Decision.WAIT_IDLE
            else -> Decision.LOCK
        }
    }
    enum class Decision { NONE, WAIT, WAIT_IDLE, WARN, LOCK }
}
