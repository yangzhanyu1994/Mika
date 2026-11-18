package com.wangzan101.mika.task

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
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

    private const val BREATH_PERIOD_MS = 2_900L
    private const val BREATH_MIN_ALPHA = 0.14f

    internal data class Completion(val runKey: String, val startedAt: Long) {
        fun remainingMs(now: Long): Long = (SUCCESS_DURATION_MS - (now - startedAt)).coerceIn(0, SUCCESS_DURATION_MS)
    }

    internal fun showsCompletion(end: TaskState, conditionCheck: Boolean): Boolean =
        !conditionCheck && end is TaskState.Succeeded && !end.doubtful && end.unconfirmed.isEmpty()

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
        val success = tasks.edgeLightCompletion.transformLatest<Completion?, Completion?> { cue ->
            val remaining = cue?.remainingMs(SystemClock.uptimeMillis()) ?: 0L
            if (remaining > 0) {
                emit(cue)
                delay(remaining)
            }
            emit(null)
        }

        scope.launch(Dispatchers.Main) {
            combine(locked, quit, ScreenAccessibility.instance) { isLocked, exited, service ->
                service?.takeUnless { isLocked || exited }
            }.distinctUntilChanged().collectLatest { service ->
                if (service == null) return@collectLatest
                var window: OverlayWindow? = null
                val successCue = mutableStateOf<Completion?>(null)
                try {
                    combine(running, success, mikaVisible) { active, completed, visible ->
                        (active && !visible) to completed
                    }.collect { (active, completed) ->
                        successCue.value = completed
                        if (!active && completed == null) {
                            window?.close()
                            window = null
                        } else if (window == null) {
                            window = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) {
                                AndroidView(
                                    factory = { context -> EdgeView(context) },
                                    modifier = Modifier.fillMaxSize(),
                                    update = { it.completion = successCue.value },
                                )
                            }?.also { it.setFullScreen(true) }
                        }
                    }
                } finally {
                    window?.close()
                }
            }
        }
    }

    // Keep brightness on the overlay View's own frame loop. A non-focusable accessibility
    // window does not need a Compose animation to keep requesting redraws.
    private class EdgeView(context: Context) : View(context) {
        private val reducedMotion = animationsOff(context)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startedAt = SystemClock.uptimeMillis()
        private var radius = 1f
        private var scaleY = 1f
        private var blue: Shader? = null
        private var green: Shader? = null

        var completion: Completion? = null
            set(value) {
                if (field == value) return
                field = value
                if (value == null) startedAt = SystemClock.uptimeMillis()
                invalidate()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            radius = (84f * resources.displayMetrics.density).coerceAtMost(w * 0.2f).coerceAtLeast(1f)
            scaleY = (h * 0.58f).coerceAtLeast(1f) / radius
            blue = gradient(0x9E16C7FF, 0x572877E9, 0x142877E9, 0x002877E9)
            green = gradient(0x9E48F49E, 0x5718BD77, 0x1418BD77, 0x0018BD77)
        }

        private fun gradient(vararg colors: Long): Shader = RadialGradient(
            0f, 0f, radius, colors.map(Long::toInt).toIntArray(),
            floatArrayOf(0f, 0.32f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val cue = completion
            val elapsed = now - (cue?.startedAt ?: startedAt)
            val brightness = when {
                reducedMotion -> 1f
                cue != null -> completionAlpha(elapsed)
                else -> breathingAlpha(elapsed)
            }
            paint.shader = (if (cue == null) blue else green) ?: return
            paint.alpha = (brightness * 255).toInt()
            drawSide(canvas, 0f)
            drawSide(canvas, width.toFloat())
            if (!reducedMotion && (cue == null || elapsed < SUCCESS_DURATION_MS)) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawSide(canvas: Canvas, x: Float) {
            val saved = canvas.save()
            canvas.translate(x, 0.23f * height)
            canvas.scale(1f, scaleY)
            canvas.drawCircle(0f, 0f, radius, paint)
            canvas.restoreToCount(saved)
        }
    }

    internal fun breathingAlpha(elapsedMs: Long): Float {
        val phase = (elapsedMs.coerceAtLeast(0) % BREATH_PERIOD_MS).toDouble() / BREATH_PERIOD_MS
        val wave = ((1.0 + cos(2 * PI * phase)) / 2).toFloat()
        return BREATH_MIN_ALPHA + (1f - BREATH_MIN_ALPHA) * wave
    }

    internal fun completionAlpha(elapsedMs: Long): Float {
        if (elapsedMs !in 0L until SUCCESS_DURATION_MS) return 0f
        val phase = elapsedMs % FLASH_PERIOD_MS
        if (phase >= FLASH_LIT_MS) return 0f
        return ((1.0 - cos(2 * PI * phase / FLASH_LIT_MS)) / 2).toFloat()
    }
}
