package com.wangzan101.mika.ui

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal enum class AuraOrbMood { Idle, Working, Succeeded }

@Composable
internal fun AuraOrb(mood: AuraOrbMood, reduceMotion: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val moving = mood == AuraOrbMood.Working
    val pace = animateFloatAsState(if (moving) 1f else 0f,
        if (reduceMotion) snap() else tween(if (moving) 750 else 2300), label = "aura-motion")
    val blue = animateFloatAsState(if (moving) 1f else 0f,
        if (reduceMotion) snap() else tween(1250), label = "aura-blue")
    val green = animateFloatAsState(if (mood == AuraOrbMood.Succeeded) 1f else 0f,
        if (reduceMotion) snap() else tween(1800), label = "aura-green")
    var phase by remember { mutableFloatStateOf(0f) }
    val mesh = remember { OrbParticles() }

    LaunchedEffect(lifecycle, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withFrameNanos { it }
            while (isActive) {
                delay(24)
                val frame = withFrameNanos { it }
                val elapsed = ((frame - last) / 1_000_000_000f).coerceAtMost(0.08f)
                phase += elapsed * (0.12f + pace.value * 1.6f)
                last = frame
            }
        }
    }

    Canvas(Modifier.widthIn(max = 368.dp).fillMaxWidth(0.97f).aspectRatio(1f).testTag("aura-particle-orb")) {
        mesh.project(phase, if (reduceMotion) 0f else pace.value, size.width)
        val dim = lerp(lerp(Color(0xFF345893), Color(0xFF064BCD), blue.value), Color(0xFF08744E), green.value)
        val lit = lerp(lerp(Color(0xFFD4ECFF), Color(0xFF70E5FF), blue.value), Color(0xFF91FFD0), green.value)
        drawIntoCanvas { canvas -> mesh.paint(canvas.nativeCanvas, size.width, dim, lit) }
    }
}

/** Points fill the sphere's volume, with extra density near its surface. Depth controls perspective and brightness. */
private class OrbParticles {
    private data class Particle(val x: Float, val y: Float, val z: Float, val glint: Float)
    private val random = Random(790)
    private val particles = Array(2450) {
        val height = random.nextFloat() * 2 - 1
        val azimuth = random.nextFloat() * (2 * PI).toFloat()
        val width = sqrt(1 - height * height)
        val layer = if (it % 3 == 0) 0.32f + random.nextFloat() * 0.64f else 0.78f + random.nextFloat() * 0.22f
        Particle(cos(azimuth) * width * layer, height * layer, sin(azimuth) * width * layer, random.nextFloat())
    }
    private val buckets = Array(12) { FloatArray(particles.size * 2) }
    private val counts = IntArray(buckets.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun project(phase: Float, energy: Float, side: Float) {
        counts.fill(0)
        val angle = phase * 0.38f
        val turn = sin(angle)
        val face = cos(angle)
        val breathe = 1f + sin(phase * 1.3f) * 0.012f
        val radius = side * 0.416f * breathe
        for (point in particles) {
            val x = point.x * face - point.z * turn
            val z = point.x * turn + point.z * face
            val y = point.y
            val wave = sin(x * 5.1f + y * 6.3f + z * 3.8f - phase * 2f)
            val swell = 1f + wave * (0.016f + energy * 0.092f)
            val perspective = 3.5f / (3.5f - z * 0.52f)
            val ripple = (wave + 1f) * 0.5f
            val depth = ((z + 1f) * 0.5f).coerceIn(0f, 1f)
            val brightness = depth * 0.45f + point.glint * 0.42f + ripple * (0.04f + energy * 0.12f)
            val bucket = (brightness * (buckets.size - 1)).toInt().coerceIn(0, buckets.lastIndex)
            val i = counts[bucket]
            val points = buckets[bucket]
            points[i] = side * 0.5f + x * radius * swell * perspective
            points[i + 1] = side * 0.5f + (y * swell + wave * energy * 0.055f) * radius * perspective
            counts[bucket] += 2
        }
    }

    fun paint(canvas: android.graphics.Canvas, side: Float, dim: Color, lit: Color) {
        for (index in buckets.indices) {
            if (counts[index] == 0) continue
            val light = index.toFloat() / buckets.lastIndex
            paint.strokeWidth = (side * (0.0018f + 0.0042f * light * light)).coerceAtLeast(0.7f)
            paint.color = lerp(dim, lit, light * light).copy(alpha = 0.16f + light * 0.81f).toArgb()
            canvas.drawPoints(buckets[index], 0, counts[index], paint)
        }
    }
}
