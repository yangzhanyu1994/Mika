package com.wangzan101.mika.capsule

import android.content.Context
import android.view.Gravity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 *
 * The four chips share one flight clock ([flight]): each chip used to own its own
 * Animatable in its own window, so the windows started at different times and the
 * bottom two chips were still flying while the top two had already landed — and
 * because a straight flight from the anchor squeezes neighbouring chips together,
 * they visibly overlapped on every open. Now all four read the same progress, the
 * flight only covers the last quarter of the trip, and the chips grow from 70%
 * while fading in, so they never overlap at any point of the flight.
 * Frames are measured once per opening and reused for the settle/close steps;
 * only [relayout] (capsule moved or configuration changed) measures again.
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
    private val flight = Animatable(0f)
    private var flightJob: Job? = null
    private var open = false

    companion object {
        /** 飞出只走最后这段路程:从锚点整段飞过来时,相邻芯片在途中会被挤到一起。 */
        internal const val FLIGHT_FRACTION = 0.25f
        private const val FLIGHT_MS = 240
    }

    fun showing(): Boolean = open && !closing.value

    fun toggle() {
        if (!scope.isActive) return
        if (open && !closing.value) close()
        else if (!open) openMenu()
    }

    fun close() {
        if (!open || closing.value) return
        settled.value = false
        place(path = true)
        closing.value = true
        flightJob?.cancel()
        flightJob = scope.launch {
            if (reduceMotion()) flight.snapTo(0f)
            else flight.animateTo(0f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
            release()
        }
    }

    fun relayout() {
        if (!open || closing.value) return
        val laid = measure() ?: return
        laid.forEachIndexed { index, frame -> frames[index] = frame }
        place(path = !settled.value)
    }

    fun release() {
        flightJob?.cancel()
        flightJob = null
        windows.forEach { it?.close() }
        windows.fill(null)
        frames.fill(null)
        closing.value = false
        settled.value = false
        open = false
    }

    private fun openMenu() {
        val overlays = host() ?: return
        val laid = measure() ?: return
        closing.value = false
        settled.value = reduceMotion()
        laid.forEachIndexed { index, frame ->
            frames[index] = frame
            windows[index]?.close()
            val window = overlays.open(placement(frame, path = true)) {
                ArcChip(
                    frame = { frames[index] ?: frame },
                    flight = flight,
                    closing = closing,
                    settled = settled,
                    onClick = {
                        onAction(frame.action)
                        close()
                    },
                )
            }
            window?.resize(frame.windowWidth, frame.windowHeight)
            windows[index] = window
        }
        open = windows.any { it != null }
        flightJob?.cancel()
        flightJob = scope.launch {
            if (reduceMotion()) flight.snapTo(1f)
            else {
                flight.snapTo(0f)
                flight.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
            }
            onArrived()
        }
        // 减动效时芯片首帧就该落在位子上:窗口直接摆到芯片大小,不等动画跑完。
        if (settled.value) place(path = false)
    }

    private fun onArrived() {
        if (closing.value || settled.value) return
        settled.value = true
        place(path = false)
    }

    /** 落位/收起只用开菜单时量好的那一版位置,不再重算:两次量出来的位置一旦不一致,芯片就会跳一下。 */
    private fun place(path: Boolean) {
        frames.forEachIndexed { index, frame ->
            val f = frame ?: return@forEachIndexed
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
    // 直接拿 Animatable 本身:把它当 State<Float> 传,编译报类型不匹配。
    flight: Animatable<Float, AnimationVector1D>,
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
    // 四个芯片共用一个飞行进度:各自计时会在不同窗口里错开,落位有先有后。
    // 只飞最后 1/4 路程并同时由小变大、由透明变不透明:全程相邻芯片都不重叠。
    val progress = if (settled.value && !closing.value) 1f else flight.value
    Box(Modifier.requiredSize(with(density) { width.toDp() }, with(density) { height.toDp() })) {
        ArcChipRow(
            current,
            onClick,
            Modifier.offset(with(density) { rowX.toDp() }, with(density) { rowY.toDp() }).graphicsLayer {
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
