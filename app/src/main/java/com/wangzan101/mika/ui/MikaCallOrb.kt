package com.wangzan101.mika.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val TURN = (2 * PI).toFloat()

private data class CallParticle(val x: Float, val y: Float, val z: Float)

/** Latitude bands extend the app's dotted Think orb into a hollow spherical surface. */
private val callParticles = buildList {
    for (band in -5..5) {
        val latitude = band * 0.275f
        val ringRadius = cos(latitude)
        val count = (ringRadius * 26).roundToInt()
        repeat(count) { index ->
            val longitude = TURN * (index + if (band % 2 == 0) 0f else 0.5f) / count
            add(CallParticle(ringRadius * cos(longitude), sin(latitude), ringRadius * sin(longitude)))
        }
    }
}

@Composable
internal fun MikaCallOrb(
    diameter: Dp,
    speaking: Boolean,
    input: Float,
    output: Float,
) {
    val moving = !MikaTheme.reduceMotion
    val dark = MikaTheme.colors.isDark
    val phase = if (moving) rememberInfiniteTransition(label = "call orb").animateFloat(
        initialValue = 0f, targetValue = TURN,
        animationSpec = infiniteRepeatable(tween(4_800, easing = LinearEasing)),
        label = "sphere rotation",
    ) else null
    val voice by animateFloatAsState(
        targetValue = if (moving) (if (speaking) output else input).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(if (moving) 140 else 0), label = "call audio level",
    )
    val speakingBlend by animateFloatAsState(
        targetValue = if (speaking) 1f else 0f,
        animationSpec = tween(if (moving) 230 else 0), label = "call orb color",
    )
    Canvas(Modifier.size(diameter)) {
        val t = phase?.value ?: 0.65f
        val blend = speakingBlend
        val radius = size.minDimension * 0.415f * (1f + 0.025f * sin(t * 2f) + voice * (-0.07f + blend * 0.17f))
        val color = lerp(
            if (dark) Color(0xFF36CFFF) else Color(0xFF007BBA),
            if (dark) Color(0xFFB293FF) else Color(0xFF7951D9),
            blend,
        )
        val heading = cos(t)
        val turn = sin(t)
        val tilt = 0.27f + 0.08f * sin(t)
        val around = cos(tilt)
        val slope = sin(tilt)
        for (point in callParticles) {
            val x = point.x * heading - point.z * turn
            val orbitZ = point.x * turn + point.z * heading
            val y = point.y * around - orbitZ * slope
            val z = point.y * slope + orbitZ * around
            val depth = (z + 1f) / 2f
            val side = x * (-0.92f + 1.84f * blend) + y * (0.28f - 0.52f * blend)
            val light = ((side + 0.22f) / 1.19f).coerceIn(0f, 1f)
            val alpha = 0.035f + light * light * (0.23f + 0.72f * depth)
            val dotSize = radius * (0.013f + 0.019f * depth + 0.004f * voice)
            val perspective = 0.94f + 0.10f * z
            val position = center + Offset(x * radius * perspective, y * radius * perspective)
            if (alpha > 0.27f) drawCircle(color, dotSize * 2f, position, alpha = alpha * 0.12f)
            drawCircle(color, dotSize, position, alpha = alpha)
        }
        if (blend > 0.01f && moving) {
            repeat(9) { i ->
                val drift = (t / TURN * 2f + i / 9f) % 1f
                val a = -0.95f + i * 0.22f
                val spread = radius * (1.01f + drift * (0.12f + 0.09f * voice))
                val position = center + Offset(cos(a) * spread, sin(a) * spread)
                drawCircle(color, radius * 0.014f, position, alpha = (1f - drift) * blend * voice * 0.68f)
            }
        }
    }
}
