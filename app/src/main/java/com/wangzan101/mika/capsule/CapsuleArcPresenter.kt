package com.wangzan101.mika.capsule

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.animation.PathInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayHost
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

data class ArcAnchor(
    val x: Float,
    val y: Float,
    val dockRight: Boolean,
    val screenW: Float,
    val screenH: Float,
    val insetLeft: Float = 0f,
    val insetTop: Float = 0f,
    val insetRight: Float = 0f,
    val insetBottom: Float = 0f,
)

/**
 * One window per action. It is placed at the travel bounds while the chip moves,
 * then once more at the chip itself when the move finishes. The move is a graphics
 * transform, so the window is not laid out again on every frame. After it settles,
 * only the chip receives touches.
 */
class CapsuleArcPresenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: () -> OverlayHost?,
    private val anchor: () -> ArcAnchor?,
    private val onAction: (CapsuleArcAction) -> Unit,
    private val reduceMotion: () -> Boolean = { false },
) {
    private val windows = arrayOfNulls<OverlayWindow>(CapsuleArc.order.size)
    private val frames = arrayOfNulls<ArcFrame>(CapsuleArc.order.size)
    private val closing = mutableStateOf(false)
    private val settled = mutableStateOf(false)
    /**
     * 四个芯片共用一个由 Android ValueAnimator 驱动的飞行进度（不依赖 Compose MonotonicFrameClock，
     * 避免在主线程 CoroutineScope 里调用 Animatable.animateTo 抛出缺少 FrameClock 的异常）。
     */
    private val flight = mutableFloatStateOf(0f)
    private var animator: ValueAnimator? = null
    private var open = false

    companion object {
        /** 飞出只走最后这段路程:从锚点整段飞过来时,相邻芯片在途中会被挤到一起。 */
        internal const val FLIGHT_FRACTION = 0.25f
        private const val FLIGHT_MS = 240L
        private val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }

    fun showing(): Boolean = open && !closing.value

    fun toggle() {
        if (!scope.isActive) return
        if (open && !closing.value) close()
        else if (!open) openMenu()
    }

    fun close() {
        if (!open || closing.value) return
        cancelAnimator()
        settled.value = false
        place(path = true, remeasure = false)
        closing.value = true
        if (reduceMotion()) {
            flight.floatValue = 0f
            release()
            return
        }
        animateFlight(flight.floatValue, 0f) { release() }
    }

    fun relayout() {
        if (!open || closing.value) return
        place(path = !settled.value, remeasure = true)
    }

    fun release() {
        cancelAnimator()
        windows.forEach { it?.close() }
        windows.fill(null)
        frames.fill(null)
        closing.value = false
        settled.value = false
        flight.floatValue = 0f
        open = false
    }

    private fun openMenu() {
        val overlays = host() ?: return
        val laid = measure() ?: return
        cancelAnimator()
        val instant = reduceMotion()
        closing.value = false
        settled.value = instant
        flight.floatValue = if (instant) 1f else 0f
        laid.forEachIndexed { index, frame ->
            frames[index] = frame
            windows[index]?.close()
            val window = overlays.open(placement(frame, path = !instant)) {
                ArcChip(
                    frame = { frames[index] ?: frame },
                    flight = flight,
                    closing = closing,
                    settled = settled,
                    onClick = {
                        // 截屏提问先立刻撤掉四个浮窗，避免把弧形按钮截进屏幕图里
                        if (frame.action == CapsuleArcAction.ScreenshotAsk) release() else close()
                        onAction(frame.action)
                    },
                )
            }
            window?.resize(
                if (instant) frame.chipWidth else frame.windowWidth,
                if (instant) frame.chipHeight else frame.windowHeight,
            )
            windows[index] = window
        }
        open = windows.any { it != null }
        if (!open) {
            release()
            return
        }
        if (!instant) {
            animateFlight(0f, 1f) {
                if (open && !closing.value) {
                    settled.value = true
                    place(path = false, remeasure = false)
                }
            }
        }
    }

    private fun animateFlight(from: Float, to: Float, onEnd: () -> Unit) {
        val anim = ValueAnimator.ofFloat(from, to).apply {
            duration = FLIGHT_MS
            interpolator = FAST_OUT_SLOW_IN
            addUpdateListener { flight.floatValue = it.animatedValue as Float }
            var cancelled = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (!cancelled) onEnd()
                }
            })
        }
        animator = anim
        anim.start()
    }

    private fun cancelAnimator() {
        animator?.cancel()
        animator = null
    }

    private fun place(path: Boolean, remeasure: Boolean) {
        val laid = if (remeasure) measure() ?: return else frames.toList()
        laid.forEachIndexed { index, frame ->
            val f = frame ?: return@forEachIndexed
            frames[index] = f
            windows[index]?.place(placement(f, path))
            // 落位后窗口也缩到芯片大小:只挪位置不缩尺寸,四个飞行大框还会互相盖着,点按会被最上面那个吃掉。
            windows[index]?.resize(
                if (path) f.windowWidth else f.chipWidth,
                if (path) f.windowHeight else f.chipHeight,
            )
        }
    }

    private fun measure(): List<ArcFrame>? {
        val a = anchor() ?: return null
        val density = context.resources.displayMetrics.density
        val fontScale = context.resources.configuration.fontScale
        val widths = CapsuleArc.order.map { labelWidth(labelOf(it), density, fontScale) }
        return CapsuleArc.layout(
            a.x, a.y, a.dockRight, a.screenW, a.screenH, density, fontScale,
            a.insetLeft, a.insetTop, a.insetRight, a.insetBottom, widths,
        )
    }

    private fun placement(frame: ArcFrame, path: Boolean) = OverlayPlacement(
        Gravity.TOP or Gravity.LEFT,
        if (path) frame.windowLeft else frame.chipLeft,
        if (path) frame.windowTop else frame.chipTop,
    )

    private fun labelOf(action: CapsuleArcAction): String = when (action) {
        CapsuleArcAction.NewChat -> context.getString(R.string.capsule_arc_new)
        CapsuleArcAction.VoiceCall -> context.getString(R.string.capsule_arc_voice)
        CapsuleArcAction.ScreenshotAsk -> context.getString(R.string.capsule_arc_screen)
        CapsuleArcAction.HideAssistant -> context.getString(R.string.capsule_arc_hide)
    }

    private fun labelWidth(text: String, density: Float, fontScale: Float): Float {
        val em = 14f * fontScale.coerceIn(1f, 1.6f) * density
        return text.length * em + 16f * density
    }
}

@Composable
private fun ArcChip(
    frame: () -> ArcFrame,
    flight: State<Float>,
    closing: State<Boolean>,
    settled: State<Boolean>,
    onClick: () -> Unit,
) {
    val current = frame()
    val density = LocalDensity.current
    val path = !settled.value
    val width = if (path) current.windowWidth else current.chipWidth
    val height = if (path) current.windowHeight else current.chipHeight
    val rowX = if (path) current.chipLeft - current.windowLeft else 0
    val rowY = if (path) current.chipTop - current.windowTop else 0
    val isSettledOpen = settled.value && !closing.value
    Box(Modifier.requiredSize(with(density) { width.toDp() }, with(density) { height.toDp() })) {
        ArcChipRow(
            current,
            onClick,
            // 在绘制阶段读取 flight.value，避免每帧触发四个 ComposeView 全量重组
            Modifier.offset(with(density) { rowX.toDp() }, with(density) { rowY.toDp() }).graphicsLayer {
                val progress = if (isSettledOpen) 1f else flight.value
                translationX = (1f - progress) * current.fromAnchorX * CapsuleArcPresenter.FLIGHT_FRACTION
                translationY = (1f - progress) * current.fromAnchorY * CapsuleArcPresenter.FLIGHT_FRACTION
                scaleX = 0.7f + 0.3f * progress
                scaleY = 0.7f + 0.3f * progress
                alpha = progress.coerceIn(0f, 1f)
            },
        )
    }
}

@Composable
private fun ArcChipRow(frame: ArcFrame, onClick: () -> Unit, motion: Modifier) {
    val label = when (frame.action) {
        CapsuleArcAction.NewChat -> tr(R.string.capsule_arc_new)
        CapsuleArcAction.VoiceCall -> tr(R.string.capsule_arc_voice)
        CapsuleArcAction.ScreenshotAsk -> tr(R.string.capsule_arc_screen)
        CapsuleArcAction.HideAssistant -> tr(R.string.capsule_arc_hide)
    }
    val icon = when (frame.action) {
        CapsuleArcAction.NewChat -> MikaIcons.New
        CapsuleArcAction.VoiceCall -> MikaIcons.Mic
        CapsuleArcAction.ScreenshotAsk -> MikaIcons.Camera
        CapsuleArcAction.HideAssistant -> MikaIcons.EyeOff
    }
    MikaTheme {
        val c = MikaTheme.colors
        val shape = RoundedCornerShape(8.dp)
        Row(
            motion,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (frame.labelOnLeft) ArcLabel(label, shape)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.surface)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, Modifier.size(20.dp), tint = c.ink)
            }
            if (!frame.labelOnLeft) ArcLabel(label, shape)
        }
    }
}

@Composable
private fun ArcLabel(text: String, shape: RoundedCornerShape) {
    val c = MikaTheme.colors
    Text(
        text,
        Modifier.clip(shape).background(c.surface).border(0.5.dp, c.glassEdge, shape).padding(horizontal = 8.dp, vertical = 4.dp),
        color = c.ink,
        style = MikaType.Caption,
        maxLines = 1,
    )
}
