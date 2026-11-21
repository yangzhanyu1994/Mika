package com.wangzan101.mika.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
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

enum class OrbMotion {
    Idle, Listening, Connecting, Thinking, Searching, Observing, Working, Writing, Waiting, Complete, Speaking,
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

/** All nine thinking-orbs effects, mapped in [ThinkingOrbMap]. */
@Composable
internal fun ResearchThinkingOrb(
    phase: OrbMotion,
    diameter: Dp = 24.dp,
    animated: Boolean = true,
    level: () -> Float = { 0f },
    color: Color = MikaTheme.colors.accent,
) {
    ThinkingOrbView(phase, diameter, animated = animated, level = level, color = color)
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
