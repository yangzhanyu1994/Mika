package com.wangzan101.mika.capsule

import android.content.Context
import android.view.Gravity
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    private val hidden = mutableIntStateOf(0)
    private var open = false

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
    }

    fun relayout() {
        if (!open || closing.value) return
        place(path = !settled.value)
    }

    fun release() {
        windows.forEach { it?.close() }
        windows.fill(null)
        frames.fill(null)
        closing.value = false
        settled.value = false
        hidden.intValue = 0
        open = false
    }

    private fun openMenu() {
        val overlays = host() ?: return
        val laid = measure() ?: return
        closing.value = false
        settled.value = reduceMotion()
        hidden.intValue = 0
        laid.forEachIndexed { index, frame ->
            frames[index] = frame
            windows[index]?.close()
            val window = overlays.open(placement(frame, path = true)) {
                ArcChip(
                    frame = { frames[index] ?: frame },
                    closing = closing,
                    settled = settled,
                    reduceMotion = reduceMotion(),
                    onClick = {
                        onAction(frame.action)
                        close()
                    },
                    onArrived = { onArrived() },
                    onHidden = { onHidden() },
                )
            }
            window?.resize(frame.windowWidth, frame.windowHeight)
            windows[index] = window
        }
        open = windows.any { it != null }
        if (settled.value) place(path = false)
    }

    private fun onArrived() {
        if (closing.value || settled.value) return
        settled.value = true
        place(path = false)
    }

    private fun onHidden() {
        hidden.intValue += 1
        if (hidden.intValue >= windows.count { it != null }) release()
    }

    private fun place(path: Boolean) {
        val laid = measure() ?: return
        laid.forEachIndexed { index, frame ->
            frames[index] = frame
            windows[index]?.place(placement(frame, path))
            // 落位后窗口也缩到芯片大小:只挪位置不缩尺寸,四个飞行大框还会互相盖着,点按会被最上面那个吃掉。
            windows[index]?.resize(
                if (path) frame.windowWidth else frame.chipWidth,
                if (path) frame.windowHeight else frame.chipHeight,
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
    closing: State<Boolean>,
    settled: State<Boolean>,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    onArrived: () -> Unit,
    onHidden: () -> Unit,
) {
    val travel = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(closing.value, reduceMotion) {
        val dest = if (closing.value) 0f else 1f
        if (reduceMotion) travel.snapTo(dest) else travel.animateTo(dest, tween(240, easing = FastOutSlowInEasing))
        if (closing.value) onHidden() else onArrived()
    }
    val current = frame()
    val density = LocalDensity.current
    val path = !settled.value
    val width = if (path) current.windowWidth else current.chipWidth
    val height = if (path) current.windowHeight else current.chipHeight
    val rowX = if (path) current.chipLeft - current.windowLeft else 0
    val rowY = if (path) current.chipTop - current.windowTop else 0
    val progress = if (settled.value && !closing.value) 1f else travel.value
    Box(Modifier.requiredSize(with(density) { width.toDp() }, with(density) { height.toDp() })) {
        ArcChipRow(
            current,
            onClick,
            Modifier.offset(with(density) { rowX.toDp() }, with(density) { rowY.toDp() }).graphicsLayer {
                translationX = (1f - progress) * current.fromAnchorX
                translationY = (1f - progress) * current.fromAnchorY
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
