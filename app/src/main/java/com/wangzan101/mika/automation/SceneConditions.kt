package com.wangzan101.mika.automation

import kotlinx.serialization.Serializable

@Serializable
internal enum class SceneSignal { Wifi, Bluetooth, Location }

@Serializable
internal data class SceneCondition(
    val signal: SceneSignal,
    val target: String = "",
    val name: String = "",
    val present: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Float = 200f,
) {
    val valid: Boolean
        get() = target.isNotBlank() && target.length <= 128 && name.length <= 128 && when (signal) {
            SceneSignal.Wifi -> true
            SceneSignal.Bluetooth -> target.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))
            SceneSignal.Location -> latitude != null && latitude in -90.0..90.0 &&
                longitude != null && longitude in -180.0..180.0 && radius in 100f..10000f
        }
}

@Serializable
internal data class SceneTimeWindow(val start: Int, val end: Int) {
    val valid: Boolean get() = start in 0..1439 && end in 0..1439 && start != end

    fun contains(minute: Int): Boolean =
        if (start < end) minute in start until end else minute >= start || minute < end
}

@Serializable
internal data class SceneConditions(
    val signals: List<SceneCondition>,
    val requireAll: Boolean = false,
    val window: SceneTimeWindow? = null,
) {
    val valid: Boolean
        get() = signals.size in 1..8 && signals.all { it.valid } && window?.valid != false

    /** Unknown observations never satisfy a criterion, including negative criteria. */
    fun matches(minute: Int, observed: (SceneCondition) -> Boolean?): Boolean? {
        if (window?.contains(minute) == false) return false
        val values = signals.map { rule -> observed(rule)?.let { it == rule.present } }
        return when {
            requireAll && false in values -> false
            !requireAll && true in values -> true
            null in values -> null
            else -> requireAll
        }
    }
}

/** Only a transition into a matching state triggers a scene. Unknown cancels pending execution. */
internal class SceneMatchGate {
    private var lastKnown: Boolean? = null
    private var confirmed = false
    private var revision = 0L

    fun observe(matches: Boolean?, observedEvent: Boolean = false): Long? {
        val transitioned = matches == true && (lastKnown == false || lastKnown == null && observedEvent)
        if (matches != null) lastKnown = matches
        if (confirmed != (matches == true)) revision++
        confirmed = matches == true
        return revision.takeIf { transitioned }
    }

    fun current(token: Long): Boolean = confirmed && revision == token
}
