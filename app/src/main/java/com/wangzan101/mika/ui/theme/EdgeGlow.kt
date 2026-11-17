package com.wangzan101.mika.ui.theme

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Transparent center, with a soft wash and moving pools of light around the edge. */
@Composable
internal fun Modifier.edgeGlow(
    color: Color,
    highlight: Color,
    reducedMotion: Boolean,
    depth: Dp = 36.dp,
    intensity: () -> Float = { 1f },
): Modifier {
    val phase = if (reducedMotion) null else {
        rememberInfiniteTransition(label = "edge glow").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6_800, easing = LinearEasing)),
            label = "glow position",
        )
    }
    return drawWithCache {
        val d = depth.toPx().coerceAtMost(size.minDimension / 3f).coerceAtLeast(1f)
        val w = size.width
        val h = size.height
        val waveColors = listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.16f), color.copy(alpha = 0.04f), color.copy(alpha = 0f))
        val top = Brush.verticalGradient(waveColors, 0f, d)
        val bottom = Brush.linearGradient(waveColors, start = Offset(0f, h), end = Offset(0f, h - d))
        val left = Brush.horizontalGradient(waveColors, 0f, d)
        val right = Brush.linearGradient(waveColors, start = Offset(w, 0f), end = Offset(w - d, 0f))
        val corner = 26.dp.toPx().coerceAtMost(size.minDimension / 2f)
        val orbit = PathMeasure(Path().apply {
            addRoundRect(RectF(0f, 0f, w, h), corner, corner, Path.Direction.CW)
        }, true)
        val position = FloatArray(2)
        val radius = d * 1.37f
        val light = Brush.radialGradient(
            0f to highlight.copy(alpha = 0.51f), 0.23f to color.copy(alpha = 0.36f),
            0.65f to color.copy(alpha = 0.11f), 1f to color.copy(alpha = 0f),
            center = Offset.Zero, radius = radius,
        )
        onDrawBehind {
            val alpha = intensity().coerceIn(0f, 1f)
            drawRect(top, size = Size(w, d), alpha = alpha * 0.62f)
            drawRect(bottom, topLeft = Offset(0f, h - d), size = Size(w, d), alpha = alpha * 0.62f)
            drawRect(left, size = Size(d, h), alpha = alpha * 0.62f)
            drawRect(right, topLeft = Offset(w - d, 0f), size = Size(d, h), alpha = alpha * 0.62f)
            val p = phase?.value ?: 0.12f
            for (i in 0..2) {
                val distance = ((p + i * 0.337f) % 1f) * orbit.length
                if (orbit.getPosTan(distance, position, null)) {
                    translate(position[0], position[1]) {
                        drawCircle(light, radius, Offset.Zero, alpha = alpha * (1f - i * 0.15f))
                    }
                }
            }
        }
    }
}
