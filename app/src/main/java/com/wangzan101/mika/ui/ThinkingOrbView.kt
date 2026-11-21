package com.wangzan101.mika.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private val CallNear = Color(0xFF8EFAFF)
private val CallFar = Color(0xFF123A72)

/**
 * Draws one thinking-orbs frame. Animation runs only while the host is at least STARTED
 * and stops when that scope is cancelled, so a covered or background call does not keep spinning.
 * [callChrome] widens the cyan lit region without filling a solid disc.
 */
@Composable
internal fun ThinkingOrbView(
    motion: OrbMotion,
    diameter: Dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    callChrome: Boolean = false,
    level: () -> Float = { 0f },
    color: Color = MikaTheme.colors.accent,
) {
    val effect = ThinkingOrbMap.of(motion)
    val preset = ThinkingOrbMap.preset(diameter.value)
    val owner = LocalLifecycleOwner.current
    val current = rememberUpdatedState(effect)
    val currentPreset = rememberUpdatedState(preset)
    var seconds by remember { mutableFloatStateOf(0.8f) }
    val moving = animated && !MikaTheme.reduceMotion && !effect.paused
    LaunchedEffect(moving, owner) {
        if (!moving) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var origin = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (origin == 0L) origin = now
                    seconds = (now - origin) / 1_000_000_000f
                }
            }
        }
    }
    Canvas(modifier.size(diameter)) {
        val shown = current.value
        val sizePx = size.minDimension
        val t = if (moving) seconds * ThinkingOrbEngine.speed(shown.mode, currentPreset.value) else 0.85f
        val frame = ThinkingOrbEngine.frame(shown.mode, sizePx, t, currentPreset.value)
        val audio = if (shown.audio && moving) level().coerceIn(0f, 1f) else 0f
        val scale = 1f + audio * 0.045f
        for (line in frame.lines) {
            val light = if (callChrome) ThinkingOrbEngine.callLight(line.white) else 1f - line.white.coerceIn(0f, 1f)
            val ink = if (callChrome) lerp(CallFar, CallNear, light) else color
            drawLine(
                ink,
                Offset((line.x1 - sizePx / 2f) * scale + sizePx / 2f, (line.y1 - sizePx / 2f) * scale + sizePx / 2f),
                Offset((line.x2 - sizePx / 2f) * scale + sizePx / 2f, (line.y2 - sizePx / 2f) * scale + sizePx / 2f),
                strokeWidth = line.w,
                alpha = line.a * (if (callChrome) 0.35f + 0.65f * light else 0.85f),
            )
        }
        for (dot in frame.dots) {
            val light = if (callChrome) ThinkingOrbEngine.callLight(dot.white) else (1f - dot.white).coerceIn(0.15f, 1f)
            val ink = if (callChrome) lerp(CallFar, CallNear, light) else color
            val alpha = (dot.a * (if (callChrome) 0.28f + 0.72f * light else 0.25f + 0.75f * light)).coerceIn(0.04f, 1f)
            val x = (dot.x - sizePx / 2f) * scale + sizePx / 2f
            val y = (dot.y - sizePx / 2f) * scale + sizePx / 2f
            if (callChrome && light > 0.72f) {
                drawCircle(ink, dot.r * 2.4f, Offset(x, y), alpha = alpha * 0.16f)
            }
            drawCircle(ink, dot.r, Offset(x, y), alpha = alpha)
        }
    }
}
