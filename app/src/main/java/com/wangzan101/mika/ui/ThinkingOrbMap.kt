package com.wangzan101.mika.ui

/**
 * Upstream thinking-orbs effect → an existing [OrbMotion].
 *
 * Every upstream mode is used. No timed carousel and no extra Mika state.
 * Listening and Speaking share `wave` because the upstream set has one waveform;
 * the call page couples that waveform to the microphone or speaker level.
 * Idle animates `ring` (breathing). Complete holds the same ring on one frame.
 */
internal data class OrbEffect(
    val mode: ThinkingOrbMode,
    val paused: Boolean = false,
    val audio: Boolean = false,
)

internal object ThinkingOrbMap {
    fun of(motion: OrbMotion): OrbEffect = when (motion) {
        OrbMotion.Idle -> OrbEffect(ThinkingOrbMode.Ring)
        OrbMotion.Listening -> OrbEffect(ThinkingOrbMode.Wave, audio = true)
        OrbMotion.Speaking -> OrbEffect(ThinkingOrbMode.Wave, audio = true)
        OrbMotion.Connecting -> OrbEffect(ThinkingOrbMode.Web)
        OrbMotion.Thinking -> OrbEffect(ThinkingOrbMode.Rubik)
        OrbMotion.Searching -> OrbEffect(ThinkingOrbMode.Globe)
        OrbMotion.Observing -> OrbEffect(ThinkingOrbMode.Morph)
        OrbMotion.Working -> OrbEffect(ThinkingOrbMode.Orbits)
        OrbMotion.Writing -> OrbEffect(ThinkingOrbMode.Ribbon)
        OrbMotion.Waiting -> OrbEffect(ThinkingOrbMode.Braid)
        OrbMotion.Complete -> OrbEffect(ThinkingOrbMode.Ring, paused = true)
    }

    fun coveredModes(): Set<ThinkingOrbMode> = OrbMotion.entries.map { of(it).mode }.toSet()

    /** Chat chips use the sparse preset. The call sphere uses the dense one. */
    fun preset(diameterDp: Float): ThinkingOrbSize =
        if (diameterDp < 48f) ThinkingOrbSize.Small else ThinkingOrbSize.Large
}
