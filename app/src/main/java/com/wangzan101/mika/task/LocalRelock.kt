package com.wangzan101.mika.task

/** Tracks one re-lock obligation across multiple runs; never records user content. */
internal class LocalRelock {
    private var baseline: Long? = null
    @Synchronized fun hasPending() = baseline != null
    @Synchronized fun unlocked(interactions: Long) { if (baseline == null) baseline = interactions }
    @Synchronized fun decide(runsRemain: Boolean, locked: Boolean, interactions: Long, canLock: Boolean): Decision {
        val recorded = baseline ?: return Decision.NONE
        if (runsRemain) return Decision.WAIT
        baseline = null
        return when {
            locked -> Decision.NONE
            !canLock || interactions != recorded -> Decision.WARN
            else -> Decision.LOCK
        }
    }
    enum class Decision { NONE, WAIT, WARN, LOCK }
}
