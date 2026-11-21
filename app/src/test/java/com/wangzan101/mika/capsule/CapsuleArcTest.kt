package com.wangzan101.mika.capsule

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleArcTest {
    @Test fun buttonsOpenOnAQuarterArcWithoutOverlapOrClipping() {
        val frames = layout(anchorX = 980f, anchorY = 1400f, dockRight = true)
        assertEquals(4, frames.size)
        assertTrue(frames.all { it.labelOnLeft })
        val gap = CapsuleArc.BUTTON_DP * 3f + CapsuleArc.GAP_DP * 3f - 1f
        for (i in frames.indices) for (j in i + 1 until frames.size) {
            val distance = hypot(frames[i].centerX - frames[j].centerX, frames[i].centerY - frames[j].centerY)
            assertTrue("buttons $i and $j overlap at $distance", distance >= gap)
        }
        frames.forEach { frame ->
            assertTrue(frame.labelLeft >= 12f)
            assertTrue(frame.labelLeft + frame.labelWidth <= 1080f - 12f)
            assertTrue(frame.centerY in 40f..2360f)
            assertTrue(frame.windowWidth < 1080)
            assertTrue(frame.windowHeight < 2400)
            assertTrue(frame.windowWidth * frame.windowHeight < 1080 * 2400 / 4)
            assertTrue(frame.centerX < 980f)
        }
    }

    @Test fun leftEdgeAndLargeTypeStayInside() {
        val frames = layout(anchorX = 80f, anchorY = 400f, dockRight = false, fontScale = 1.4f, labelWidth = 220f)
        frames.forEach { frame ->
            assertTrue(!frame.labelOnLeft)
            assertTrue(frame.labelLeft >= 8f)
            assertTrue(frame.labelLeft + frame.labelWidth <= 1080f - 8f)
            assertTrue(frame.centerX > 80f)
            assertTrue(frame.windowWidth < 1080)
        }
    }

    @Test fun theSamePathReturnsTowardTheAnchor() {
        val frame = layout(anchorX = 900f, anchorY = 1000f, dockRight = true).first()
        val openX = frame.centerX
        val closedX = frame.centerX + frame.fromAnchorX
        assertEquals(900f, closedX, 0.5f)
        assertTrue(openX < closedX)
    }

    private fun layout(
        anchorX: Float,
        anchorY: Float,
        dockRight: Boolean,
        fontScale: Float = 1f,
        labelWidth: Float = 160f,
    ) = CapsuleArc.layout(
        anchorX, anchorY, dockRight, 1080f, 2400f, density = 3f, fontScale = fontScale,
        insetLeft = 0f, insetTop = 80f, insetRight = 0f, insetBottom = 80f,
        labelWidths = List(4) { labelWidth },
    )
}
