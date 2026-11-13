package com.wangzan101.mika.automation

/** Suppresses initial fixes and inaccurate boundary readings rather than inventing a crossing. */
internal class LocationTransitionGate {
    private val inside = mutableMapOf<String, Boolean>()

    fun observe(id: String, distanceMeters: Float, accuracyMeters: Float, radiusMeters: Float): Boolean? {
        if (!distanceMeters.isFinite() || !accuracyMeters.isFinite() || !radiusMeters.isFinite() ||
            distanceMeters < 0f || accuracyMeters < 0f || radiusMeters <= 0f) return null
        // A fix overlapping the boundary cannot establish on which side the phone is.
        val next = when {
            distanceMeters + accuracyMeters < radiusMeters -> true
            distanceMeters - accuracyMeters > radiusMeters -> false
            else -> return null
        }
        val previous = inside.put(id, next)
        return if (previous == null || previous == next) null else next
    }

    fun reset(id: String) { inside.remove(id) }
    fun clear() { inside.clear() }
}
