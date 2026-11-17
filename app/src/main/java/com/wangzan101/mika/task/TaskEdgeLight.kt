package com.wangzan101.mika.task

import android.view.Gravity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** 主屏边缘仅显示状态，由系统把触摸和按键交给当前 App。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object TaskEdgeLight {
    private const val SUCCESS_HOLD_MS = 550L
    private const val SUCCESS_FADE_MS = 850

    fun start(
        scope: CoroutineScope,
        tasks: TaskCenterState,
        mikaVisible: StateFlow<Boolean>,
        locked: StateFlow<Boolean>,
        quit: Flow<Boolean>,
    ) {
        val running = tasks.runs.flatMapLatest { runs ->
            val states = runs.filterNot { it.conditionCheck }.map { run ->
                run.state.map { it is TaskState.Running }.distinctUntilChanged()
            }
            if (states.isEmpty()) flowOf(false) else combine(states) { it.any { active -> active } }
        }.distinctUntilChanged()
        val success = tasks.edgeLightSuccesses.transformLatest<String, String?> { runKey ->
            emit(runKey)
            delay(SUCCESS_HOLD_MS + SUCCESS_FADE_MS)
            emit(null)
        }.onStart { emit(null) }

        scope.launch(Dispatchers.Main) {
            combine(mikaVisible, locked, quit, ScreenAccessibility.instance) { visible, isLocked, exited, service ->
                service?.takeUnless { visible || isLocked || exited }
            }.distinctUntilChanged().collectLatest { service ->
                if (service == null) return@collectLatest
                var window: OverlayWindow? = null
                val successKey = mutableStateOf<String?>(null)
                try {
                    combine(running, success) { active, completed -> active to completed }.collect { (active, completed) ->
                        successKey.value = completed
                        if (!active && completed == null) {
                            window?.close()
                            window = null
                        } else if (window == null) {
                            window = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) {
                                val completedKey = successKey.value
                                key(completedKey) { Edge(completedKey != null) }
                            }?.also { it.setFullScreen(true) }
                        }
                    }
                } finally {
                    window?.close()
                }
            }
        }
    }

    @Composable
    private fun Edge(success: Boolean) {
        val opacity: State<Float> = if (success) {
            val fade = remember { Animatable(1f) }
            LaunchedEffect(Unit) {
                delay(SUCCESS_HOLD_MS)
                fade.animateTo(0f, tween(SUCCESS_FADE_MS))
            }
            fade.asState()
        } else {
            rememberInfiniteTransition(label = "edge light")
                .animateFloat(0.48f, 1f, infiniteRepeatable(tween(1_150), RepeatMode.Reverse), label = "opacity")
        }
        val brush = remember(success) {
            if (success) Brush.linearGradient(listOf(Color(0xFF09BF72), Color(0xFF9FFFC7), Color(0xFF09BF72)))
            else Brush.linearGradient(listOf(Color(0xFF1E60FF), Color(0xFF64E9FF), Color(0xFF1A74FF)))
        }
        Canvas(Modifier.fillMaxSize()) {
            val inset = 2.5.dp.toPx()
            val bounds = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f))
            val radius = CornerRadius(24.dp.toPx())
            val alpha = opacity.value
            drawRoundRect(brush, Offset(inset, inset), bounds, radius, style = Stroke(8.dp.toPx()), alpha = alpha * 0.20f)
            drawRoundRect(brush, Offset(inset, inset), bounds, radius, style = Stroke(3.dp.toPx()), alpha = alpha)
        }
    }
}
