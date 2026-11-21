package com.wangzan101.mika.capsule

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ArcAnchor(val x: Float, val y: Float, val dockRight: Boolean, val screenW: Float, val screenH: Float)

/**
 * Four chip windows, each only as large as its button and label, so the gaps
 * and the apps behind them still receive touches.
 */
class CapsuleArcPresenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: () -> OverlayHost?,
    private val anchor: () -> ArcAnchor?,
    private val onAction: (CapsuleArcAction) -> Unit,
    private val reduceMotion: () -> Boolean = { false },
) {
    private var progress = 0f
    private var target = 0f
    private var job: Job? = null
    private val windows = arrayOfNulls<OverlayWindow>(CapsuleArc.order.size)
    private var open = false

    fun showing(): Boolean = open

    fun toggle() {
        target = if (target >= 0.5f) 0f else 1f
        run()
    }

    fun close() {
        if (!open && target == 0f) return
        target = 0f
        run()
    }

    fun relayout() {
        if (open) place()
    }

    fun release() {
        job?.cancel()
        windows.forEach { it?.close() }
        windows.fill(null)
        progress = 0f
        target = 0f
        open = false
    }

    private fun run() {
        val overlays = host() ?: return
        if (!open && target > 0f) {
            CapsuleArc.order.forEachIndexed { index, action ->
                val labelOnLeft = anchor()?.dockRight != false
                windows[index] = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT)) {
                    ArcChip(action, labelOnLeft) {
                        onAction(action)
                        close()
                    }
                }
            }
            open = windows.any { it != null }
        }
        job?.cancel()
        if (reduceMotion()) {
            progress = target
            place()
            if (target == 0f) release()
            return
        }
        job = scope.launch {
            val start = progress
            val dest = target
            val steps = 14
            for (i in 1..steps) {
                if (target != dest) return@launch
                progress = start + (dest - start) * i / steps
                place()
                delay(16)
            }
            progress = dest
            place()
            if (dest == 0f) release()
        }
    }

    private fun place() {
        val a = anchor() ?: return
        val buttons = CapsuleArc.layout(a.x, a.y, a.dockRight, a.screenW, a.screenH, progress)
        val density = context.resources.displayMetrics.density
        buttons.forEachIndexed { index, button ->
            val window = windows[index] ?: return@forEachIndexed
            val buttonPx = 44f * density
            val labelPx = 108f * density
            val left = if (button.labelOnLeft) button.x - labelPx - buttonPx / 2f else button.x - buttonPx / 2f
            val top = button.y - buttonPx / 2f
            window.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, left.toInt(), top.toInt()))
            window.setVisible(progress > 0.04f)
        }
    }
}

@Composable
private fun ArcChip(action: CapsuleArcAction, labelOnLeft: Boolean, onClick: () -> Unit) {
    val label = when (action) {
        CapsuleArcAction.NewChat -> tr(R.string.capsule_arc_new)
        CapsuleArcAction.VoiceCall -> tr(R.string.capsule_arc_voice)
        CapsuleArcAction.ScreenshotAsk -> tr(R.string.capsule_arc_screen)
        CapsuleArcAction.HideAssistant -> tr(R.string.capsule_arc_hide)
    }
    val icon = when (action) {
        CapsuleArcAction.NewChat -> MikaIcons.New
        CapsuleArcAction.VoiceCall -> MikaIcons.Mic
        CapsuleArcAction.ScreenshotAsk -> MikaIcons.Camera
        CapsuleArcAction.HideAssistant -> MikaIcons.EyeOff
    }
    MikaTheme {
        val c = MikaTheme.colors
        Row(
            Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (labelOnLeft) Text(label, color = c.ink, style = MikaType.Caption)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.surface).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, Modifier.size(20.dp), tint = c.accent)
            }
            if (!labelOnLeft) Text(label, color = c.ink, style = MikaType.Caption)
        }
    }
}
