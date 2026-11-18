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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wangzan101.mika.chat.ResearchPhase
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.flow.collect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbMotion {
    Idle, Listening, Connecting, Thinking, Searching, Observing, Working, Writing, Waiting, Complete,
}

internal fun toolOrbMotion(tool: String?): OrbMotion? = when (tool) {
    null -> null
    "web_search", "knowledge_search", "skill_search", "search_files" -> OrbMotion.Searching
    "phone_find", "find_visually", "list_elements", "observe", "screenshot", "web_read_page" -> OrbMotion.Observing
    else -> OrbMotion.Working
}

internal val TaskState.orbMotion: OrbMotion
    get() = when (this) {
        TaskState.Idle -> OrbMotion.Idle
        is TaskState.Running -> when {
            progress is RunProgress.WaitingForApp -> OrbMotion.Waiting
            tool != null -> toolOrbMotion(tool) ?: OrbMotion.Working
            progress != null -> OrbMotion.Working
            !preview.isNullOrBlank() -> OrbMotion.Writing
            step == 0 -> OrbMotion.Connecting
            else -> OrbMotion.Thinking
        }
        is TaskState.Held, is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover ->
            OrbMotion.Waiting
        is TaskState.Succeeded -> OrbMotion.Complete
        is TaskState.Failed, TaskState.Cancelled -> OrbMotion.Idle
    }

internal val ResearchPhase.orbMotion: OrbMotion
    get() = when (this) {
        ResearchPhase.Thinking -> OrbMotion.Thinking
        ResearchPhase.Searching -> OrbMotion.Searching
        ResearchPhase.Writing -> OrbMotion.Writing
    }

@Composable
internal fun ResearchThinkingOrb(searching: Boolean, diameter: Dp = 24.dp, animated: Boolean = true) {
    ResearchThinkingOrb(if (searching) OrbMotion.Searching else OrbMotion.Thinking, diameter, animated)
}

/** Native dotted forms inspired by https://github.com/Jakubantalik/thinking-orbs. */
@Composable
internal fun ResearchThinkingOrb(
    phase: OrbMotion,
    diameter: Dp = 24.dp,
    animated: Boolean = true,
    level: () -> Float = { 0f },
) {
    val color = MikaTheme.colors.accent
    val moving = animated && !MikaTheme.reduceMotion && phase != OrbMotion.Idle && phase != OrbMotion.Complete
    val turn = (2 * PI).toFloat()
    val motion = if (moving) rememberInfiniteTransition(label = "assistant orb").animateFloat(
        initialValue = 0f, targetValue = turn,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "assistant orb phase",
    ) else null

    Canvas(Modifier.size(diameter)) {
        val t = motion?.value ?: 0.8f
        val r = size.minDimension * 0.39f
        val dot = size.minDimension * 0.039f
        fun point(x: Float, y: Float, alpha: Float, scale: Float = 1f) {
            drawCircle(color, dot * scale, center + Offset(x * r, y * r), alpha = alpha)
        }
        when (phase) {
            OrbMotion.Idle, OrbMotion.Thinking -> {
                val spread = 0.77f + sin(t) * 0.13f
                repeat(18) { i ->
                    val a = turn * i / 18
                    point(spread * cos(a), spread * sin(a), 0.55f + 0.24f * sin(a - t))
                }
            }
            OrbMotion.Listening, OrbMotion.Writing -> {
                val listening = phase == OrbMotion.Listening
                val amplitude = if (listening && moving) 0.24f + level().coerceIn(0f, 1f) * 0.50f else 0.31f
                for (row in -1..1) for (i in 0..8) {
                    val x = (i - 4) / 4f
                    val wave = sin(x * 3.4f - t * 2f + row * (if (listening) 0.36f else 1.25f))
                    point(x, row * 0.20f + wave * amplitude, 0.8f - 0.23f * kotlin.math.abs(x))
                }
            }
            OrbMotion.Connecting, OrbMotion.Waiting -> repeat(16) { i ->
                val a = turn * i / 16f
                val pulse = (1f + cos(a - t)) / 2f
                val spread = if (phase == OrbMotion.Connecting) 0.46f + 0.50f * pulse else 0.79f
                point(cos(a) * spread, sin(a) * spread, 0.24f + 0.7f * pulse, 0.72f + pulse * 0.46f)
            }
            OrbMotion.Searching -> for (band in -2..2) for (i in 0..8) {
                val latitude = band * 0.42f
                val a = turn * i / 9f + t
                val scan = (1f + cos(a - 2f * t)) / 2f
                point(cos(latitude) * cos(a), sin(latitude) + 0.14f * sin(a), 0.18f + 0.75f * scan * scan)
            }
            OrbMotion.Observing -> {
                val spread = 0.79f + 0.13f * sin(t)
                for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
                    point(x * spread, y * spread, 0.86f)
                    point(x * (spread - 0.27f), y * spread, 0.66f)
                    point(x * spread, y * (spread - 0.27f), 0.66f)
                }
                repeat(5) { i -> point((i - 2) * 0.24f, 0.51f * sin(t), 0.85f, 0.85f) }
            }
            OrbMotion.Working -> for (orbit in 0..2) {
                val tilt = turn * orbit / 6f
                repeat(11) { i ->
                    val a = turn * i / 11f + t * (orbit + 1)
                    val x = 0.97f * cos(a)
                    val y = 0.36f * sin(a)
                    point(x * cos(tilt) - y * sin(tilt), x * sin(tilt) + y * cos(tilt),
                        0.26f + 0.63f * (10 - i) / 10f)
                }
            }
            OrbMotion.Complete -> {
                repeat(4) { i -> point(-0.54f + i * 0.14f, i * 0.13f, 0.9f) }
                repeat(6) { i -> point(-0.12f + i * 0.14f, 0.39f - i * 0.15f, 0.9f) }
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
