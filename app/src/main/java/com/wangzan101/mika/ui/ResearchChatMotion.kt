package com.wangzan101.mika.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.flow.collect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Native dotted orb inspired by https://github.com/Jakubantalik/thinking-orbs. */
@Composable
internal fun ResearchThinkingOrb(searching: Boolean) {
    val c = MikaTheme.colors
    val motion = if (MikaTheme.reduceMotion) null else rememberInfiniteTransition(label = "research orb").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "research orb phase",
    )
    Canvas(Modifier.size(24.dp)) {
        val phase = motion?.value ?: 0.9f
        val radius = size.minDimension * 0.39f
        val dot = size.minDimension * 0.038f
        for (band in -1..1) {
            val latitude = band * 0.65f
            for (index in 0..8) {
                val angle = (index * (2 * PI / 9)).toFloat() + phase
                val depth = (1f + sin(angle)) / 2f
                val x = radius * cos(latitude) * cos(angle)
                val y = radius * (sin(latitude) + 0.25f * sin(angle))
                val scan = (1f + cos(angle - 2f * phase)) / 2f
                val alpha = if (searching) 0.18f + 0.74f * scan * scan else 0.22f + 0.70f * depth
                drawCircle(c.accent, dot * (0.8f + depth * 0.45f), center + Offset(x, y), alpha = alpha)
            }
        }
    }
}

/** Only react to newly displayed text while this screen is interactive. No vibrator permission. */
@Composable
internal fun ResearchReplyHaptics(id: String?, text: String) {
    val current = rememberUpdatedState(text)
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    LaunchedEffect(id, owner, view) {
        if (id == null) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = current.value
            var lastTick = 0L
            snapshotFlow { current.value }.collect { next ->
                val now = SystemClock.uptimeMillis()
                if (next.length > previous.length && next.startsWith(previous) &&
                    now - lastTick >= 105 && view.hasWindowFocus()
                ) {
                    lastTick = now
                    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                        else HapticFeedbackConstants.CLOCK_TICK
                    view.performHapticFeedback(effect)
                }
                previous = next
            }
        }
    }
}
