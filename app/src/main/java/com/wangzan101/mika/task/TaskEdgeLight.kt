package com.wangzan101.mika.task

import android.os.SystemClock
import android.view.Gravity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/** 主屏边缘仅显示状态，由系统把触摸和按键交给当前 App。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object TaskEdgeLight {
    private const val SUCCESS_FLASHES = 5
    private const val FLASH_PERIOD_MS = 760L
    private const val FLASH_LIT_MS = 560L
    internal const val SUCCESS_DURATION_MS = SUCCESS_FLASHES * FLASH_PERIOD_MS

    private data class SuccessCue(val startedAt: Long)

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
        val success = tasks.edgeLightSuccesses.buffer().transform<String, SuccessCue?> {
            emit(SuccessCue(SystemClock.uptimeMillis()))
            delay(SUCCESS_DURATION_MS)
            emit(null)
        }.onStart { emit(null) }

        scope.launch(Dispatchers.Main) {
            combine(mikaVisible, locked, quit, ScreenAccessibility.instance) { visible, isLocked, exited, service ->
                service?.takeUnless { visible || isLocked || exited }
            }.distinctUntilChanged().collectLatest { service ->
                if (service == null) return@collectLatest
                var window: OverlayWindow? = null
                val successCue = mutableStateOf<SuccessCue?>(null)
                try {
                    combine(running, success) { active, completed -> active to completed }.collect { (active, completed) ->
                        successCue.value = completed
                        if (!active && completed == null) {
                            window?.close()
                            window = null
                        } else if (window == null) {
                            window = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) {
                                Edge(successCue.value)
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
    private fun Edge(cue: SuccessCue?) {
        val reducedMotion = animationsOff(LocalContext.current)
        val elapsed = remember(cue) { mutableLongStateOf(0L) }
        LaunchedEffect(cue, reducedMotion) {
            if (cue != null && !reducedMotion) {
                while (elapsed.longValue < SUCCESS_DURATION_MS) {
                    withFrameNanos { elapsed.longValue = SystemClock.uptimeMillis() - cue.startedAt }
                }
            }
        }
        val center = if (cue != null || reducedMotion) null else {
            rememberInfiniteTransition(label = "task side glow").animateFloat(
                initialValue = 0.19f, targetValue = 0.29f,
                animationSpec = infiniteRepeatable(tween(3_200), RepeatMode.Reverse),
                label = "side glow height",
            )
        }
        val color = if (cue == null) Color(0xFF16C7FF) else Color(0xFF48F49E)
        val haze = if (cue == null) Color(0xFF2877E9) else Color(0xFF18BD77)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                // The same tall gradient lights both sides, with its brightest area above mid-screen.
                val depth = 84.dp.toPx().coerceAtMost(size.width * 0.2f).coerceAtLeast(1f)
                val verticalScale = (size.height * 0.58f).coerceAtLeast(1f) / depth
                val sides = floatArrayOf(0f, size.width)
                val glow = Brush.radialGradient(
                    0f to color.copy(alpha = 0.62f), 0.32f to haze.copy(alpha = 0.34f),
                    0.72f to haze.copy(alpha = 0.08f), 1f to haze.copy(alpha = 0f),
                    center = Offset.Zero, radius = depth,
                )
                onDrawBehind {
                    val alpha = if (cue == null || reducedMotion) 1f else completionAlpha(elapsed.longValue)
                    val y = (center?.value ?: 0.23f) * size.height
                    for (x in sides) {
                        translate(x, y) {
                            scale(scaleX = 1f, scaleY = verticalScale, pivot = Offset.Zero) {
                                drawCircle(glow, depth, Offset.Zero, alpha)
                            }
                        }
                    }
                }
            },
        )
    }

    internal fun completionAlpha(elapsedMs: Long): Float {
        if (elapsedMs !in 0L until SUCCESS_DURATION_MS) return 0f
        val phase = elapsedMs % FLASH_PERIOD_MS
        if (phase >= FLASH_LIT_MS) return 0f
        return ((1.0 - cos(2 * PI * phase / FLASH_LIT_MS)) / 2).toFloat()
    }
}
