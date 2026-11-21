package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** Call sphere: the same thinking-orbs frame as the chat chip, with a wider cyan lit region. */
@Composable
internal fun MikaCallOrb(
    diameter: Dp,
    motion: OrbMotion,
    input: Float,
    output: Float,
) {
    val level = if (motion == OrbMotion.Speaking) output else input
    ThinkingOrbView(motion, diameter, callChrome = true, level = { level })
}
