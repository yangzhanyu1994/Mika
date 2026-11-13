package com.wangzan101.mika.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class AmbientMode { Idle, Thinking, Executing }

/** Move the artwork itself so the existing glass contours drift as well as the overlay. */
@Composable
internal fun Modifier.liquidBackgroundMotion(reduceMotion: Boolean): Modifier {
    val phase = if (reduceMotion) null else rememberInfiniteTransition(label = "glass-artwork").animateFloat(
        0f, 6.2831855f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart), label = "artwork-phase"
    )
    return graphicsLayer {
        val t = phase?.value ?: 0f
        scaleX = if (reduceMotion) 1f else 1.06f
        scaleY = if (reduceMotion) 1f else 1.06f
        translationX = if (reduceMotion) 0f else sin(t) * size.width * 0.012f
        translationY = if (reduceMotion) 0f else (cos(t) - 1f) * size.height * 0.012f
    }
}

/** Gentle motion pauses when reduced motion is enabled. */
@Composable
internal fun Modifier.ambientGlow(mode: AmbientMode, reduceMotion: Boolean): Modifier {
    val phaseState = if (reduceMotion) null else
        rememberInfiniteTransition(label = "ambient-glow").animateFloat(
            0f, 6.2831855f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "ambient-phase"
        )
    val strengthState = animateFloatAsState(
        when (mode) { AmbientMode.Idle -> 0.09f; AmbientMode.Thinking -> 0.36f; AmbientMode.Executing -> 0.46f },
        if (reduceMotion) snap() else tween(800), label = "ambient-strength",
    )
    return drawBehind {
        val phase = phaseState?.value ?: 0f
        val strength = strengthState.value
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val dx = sin(phase) * 0.16f
        val dy = cos(phase) * 0.08f
        drawRect(Brush.radialGradient(listOf(Color(0xFF66A9FF).copy(alpha = strength), Color.Transparent),
            center = Offset(w * (0.34f + dx), h * (0.48f + dy)), radius = w * 0.82f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFBA92F4).copy(alpha = strength * 0.9f), Color.Transparent),
            center = Offset(w * (0.72f - dx), h * (0.55f - dy)), radius = w * 0.78f))
        if (mode == AmbientMode.Executing) drawRect(Brush.radialGradient(
            listOf(Color(0xFFFFC6BD).copy(alpha = strength * 0.6f), Color.Transparent),
            center = Offset(w * (0.64f + dx), h * 0.63f), radius = w * 0.60f))
        val motion = when (mode) { AmbientMode.Idle -> 0.012f; AmbientMode.Thinking -> 0.025f; AmbientMode.Executing -> 0.037f }
        val rise = sin(phase) * motion
        val sway = cos(phase) * motion
        val wave = Path().apply {
            moveTo(-w * 0.10f, h * (0.71f + rise))
            cubicTo(w * 0.25f, h * (0.62f - sway), w * 0.62f, h * (0.82f + sway), w * 1.10f, h * (0.70f - rise))
        }
        drawPath(wave, Color.White.copy(alpha = 0.42f), style = Stroke(width = 1.dp.toPx()))
        val glint = Offset(w * (0.5f + 0.42f * sin(phase)), h * (0.71f + rise))
        drawPath(wave, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f), Color.Transparent),
            center = glint, radius = w * 0.22f), style = Stroke(width = 2.dp.toPx()))
    }
}
