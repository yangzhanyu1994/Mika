package com.wangzan101.mika.capsule

import android.view.Choreographer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import com.wangzan101.mika.ui.OrbMotion
import com.wangzan101.mika.ui.ThinkingOrbEngine
import com.wangzan101.mika.ui.ThinkingOrbMap
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.max

/**
 * The floating mark while Mika is thinking, working, or waiting.
 * Frames are drawn directly. The clock does not write Compose state, and it stops
 * when the mark leaves the screen or motion is reduced.
 */
@Composable
internal fun CapsuleOrb(motion: OrbMotion, diameter: Dp, animated: Boolean, color: Color = MikaTheme.colors.ink) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(diameter).then(CapsuleOrbElement(motion, animated && !MikaTheme.reduceMotion, color)),
    )
}

private data class CapsuleOrbElement(
    val motion: OrbMotion,
    val animated: Boolean,
    val color: Color,
) : ModifierNodeElement<CapsuleOrbNode>() {
    override fun create() = CapsuleOrbNode(motion, animated, color)
    override fun update(node: CapsuleOrbNode) = node.update(motion, animated, color)
}

private class CapsuleOrbNode(
    private var motion: OrbMotion,
    private var animated: Boolean,
    private var color: Color,
) : Modifier.Node(), DrawModifierNode {
    private var nanos = 0L
    private val clock: Choreographer.FrameCallback = Choreographer.FrameCallback { now ->
        nanos = now
        invalidateDraw()
        if (isAttached && animated) Choreographer.getInstance().postFrameCallback(this.clock)
    }

    fun update(motion: OrbMotion, animated: Boolean, color: Color) {
        val was = this.animated
        this.motion = motion
        this.animated = animated
        this.color = color
        if (isAttached && animated && !was) Choreographer.getInstance().postFrameCallback(clock)
        if (!animated) Choreographer.getInstance().removeFrameCallback(clock)
        invalidateDraw()
    }

    override fun onAttach() {
        if (animated) Choreographer.getInstance().postFrameCallback(clock)
    }

    override fun onDetach() {
        Choreographer.getInstance().removeFrameCallback(clock)
    }

    override fun ContentDrawScope.draw() {
        val effect = ThinkingOrbMap.of(motion)
        val preset = ThinkingOrbMap.preset(28f)
        val sizePx = size.minDimension
        val seconds = if (animated && nanos > 0L) nanos / 1_000_000_000f else 0.85f
        val frame = ThinkingOrbEngine.frame(
            effect.mode, sizePx, seconds * ThinkingOrbEngine.speed(effect.mode, preset), preset,
        )
        for (line in frame.lines) {
            drawLine(
                color,
                Offset(line.x1, line.y1),
                Offset(line.x2, line.y2),
                strokeWidth = max(line.w, sizePx * 0.012f),
                alpha = line.a.coerceIn(0.2f, 1f),
            )
        }
        for (dot in frame.dots) {
            drawCircle(
                color,
                radius = max(dot.r, sizePx * 0.018f),
                center = Offset(dot.x, dot.y),
                alpha = dot.a.coerceIn(0.25f, 1f),
            )
        }
    }
}
