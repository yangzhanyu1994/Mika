package com.wangzan101.mika.core.location

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin

/** Precise, approximate, or no location permission. Background is a separate grant. */
enum class LocationAccess { None, Coarse, Fine }

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val ageMs: Long,
    val provider: String,
    val fromCache: Boolean,
)

data class LocationDecision(
    val failure: String? = null,
    val sample: LocationSample? = null,
    /** True only when the coordinates come from a stored fix, not a new reading. */
    val cached: Boolean = false,
)

/**
 * Decides what a location answer may say. A fix older than [FRESH_MS] is not current.
 * Anything older than [MAX_CACHE_MS] is omitted. No address is invented here.
 */
object DeviceLocationPolicy {
    const val FRESH_MS = 120_000L
    const val MAX_CACHE_MS = 15 * 60_000L

    fun backgroundRequired(origin: RunOrigin, attendance: Attendance): Boolean =
        attendance == Attendance.Unattended || origin == RunOrigin.Trigger ||
            origin == RunOrigin.Channel || origin == RunOrigin.Mcp

    fun decide(
        access: LocationAccess,
        servicesEnabled: Boolean,
        backgroundGranted: Boolean,
        autoEnabled: Boolean,
        origin: RunOrigin,
        attendance: Attendance,
        reading: LocationSample?,
        cached: LocationSample?,
    ): LocationDecision {
        val background = backgroundRequired(origin, attendance)
        if (background && !autoEnabled) return LocationDecision("auto_off")
        if (access == LocationAccess.None) return LocationDecision("no_permission")
        if (background && !backgroundGranted) return LocationDecision("needs_background")
        if (!servicesEnabled) return LocationDecision("services_off")
        val live = reading?.takeIf { !it.fromCache && fresh(it) }
        if (live != null) return LocationDecision(sample = live, cached = false)
        val stored = cached?.takeIf { fresh(it) }
        if (stored != null) return LocationDecision(sample = stored.copy(fromCache = true), cached = true)
        return LocationDecision("no_fix")
    }

    private fun fresh(sample: LocationSample): Boolean =
        sample.ageMs in 0..FRESH_MS &&
            sample.latitude in -90.0..90.0 && sample.longitude in -180.0..180.0 &&
            sample.accuracyMeters in 0.1f..5_000f
}
