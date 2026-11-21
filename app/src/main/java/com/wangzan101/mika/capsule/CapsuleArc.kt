package com.wangzan101.mika.capsule

/**
 * Tap action for the floating capsule. [QuickWindow] keeps the existing card.
 * [QuickMenu] is the default after this change: four real actions travel out on an arc
 * and return along the same path.
 */
enum class CapsuleTapAction {
    QuickMenu,
    QuickWindow;

    companion object {
        fun parse(name: String?): CapsuleTapAction =
            entries.firstOrNull { it.name == name } ?: QuickMenu
    }
}

enum class CapsuleArcAction { NewChat, VoiceCall, ScreenshotAsk, HideAssistant }

data class ArcFrame(
    val action: CapsuleArcAction,
    val centerX: Float,
    val centerY: Float,
    val labelLeft: Float,
    val labelTop: Float,
    val labelWidth: Float,
    val labelHeight: Float,
    val labelOnLeft: Boolean,
    val chipLeft: Int,
    val chipTop: Int,
    val chipWidth: Int,
    val chipHeight: Int,
    val windowLeft: Int,
    val windowTop: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    /** Button-center delta from the open position back to the capsule. Progress 0 uses all of it. */
    val fromAnchorX: Float,
    val fromAnchorY: Float,
)

/**
 * Four buttons on a quarter-arc. A right dock opens leftward with labels on the left.
 * A left dock mirrors. Centers stay at least one button plus a gap apart, and the
 * labels stay inside the screen insets. Window rectangles cover the travel path
 * and the label, and stay smaller than the screen.
 */
object CapsuleArc {
    const val BUTTON_DP = 44f
    const val GAP_DP = 12f
    private const val LABEL_GAP_DP = 8f
    private const val MARGIN_DP = 8f
    private const val SPAN = (Math.PI / 2.0).toFloat()

    val order = listOf(
        CapsuleArcAction.NewChat,
        CapsuleArcAction.VoiceCall,
        CapsuleArcAction.ScreenshotAsk,
        CapsuleArcAction.HideAssistant,
    )

    fun layout(
        anchorX: Float,
        anchorY: Float,
        dockRight: Boolean,
        screenW: Float,
        screenH: Float,
        density: Float,
        fontScale: Float = 1f,
        insetLeft: Float = 0f,
        insetTop: Float = 0f,
        insetRight: Float = 0f,
        insetBottom: Float = 0f,
        labelWidths: List<Float> = List(order.size) { 96f * density * fontScale },
    ): List<ArcFrame> {
        val button = BUTTON_DP * density
        val gap = GAP_DP * density
        val labelGap = LABEL_GAP_DP * density
        val margin = MARGIN_DP * density
        val labelHeight = (28f * fontScale.coerceIn(1f, 1.6f)) * density
        val count = order.size
        val step = if (count <= 1) SPAN else SPAN / (count - 1)
        val radius = if (count <= 1) button else (button + gap) / (2f * kotlin.math.sin(step / 2f))
        val direction = if (dockRight) -1f else 1f
        val raw = order.mapIndexed { index, action ->
            val angle = -SPAN / 2f + step * index
            val width = labelWidths.getOrElse(index) { 96f * density }
            Triple(action, anchorX + direction * kotlin.math.cos(angle) * radius, anchorY + kotlin.math.sin(angle) * radius) to width
        }
        val leftLimit = insetLeft + margin
        val rightLimit = (screenW - insetRight - margin).coerceAtLeast(leftLimit)
        val topLimit = insetTop + margin
        val bottomLimit = (screenH - insetBottom - margin).coerceAtLeast(topLimit)
        fun bounds(points: List<Pair<Pair<CapsuleArcAction, Float>, Float>>): Pair<Float, Float> {
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            points.forEach { (point, width) ->
                val center = point.second
                val labelLeft = if (dockRight) center - button / 2f - labelGap - width else center + button / 2f + labelGap
                val labelRight = labelLeft + width
                minX = minOf(minX, center - button / 2f, labelLeft)
                maxX = maxOf(maxX, center + button / 2f, labelRight)
            }
            return minX to maxX
        }
        val (minX, maxX) = bounds(raw.map { (point, width) -> (point.first to point.second) to width })
        val shiftX = fit(minX, maxX, leftLimit, rightLimit)
        val minY = raw.minOf { it.first.third } - button / 2f
        val maxY = raw.maxOf { it.first.third } + button / 2f
        val shiftY = fit(minY, maxY, topLimit, bottomLimit)
        val pad = 4f * density
        return raw.map { (point, width) ->
            val (action, x0, y0) = point
            val centerX = (x0 + shiftX).coerceIn(leftLimit + button / 2f, (rightLimit - button / 2f).coerceAtLeast(leftLimit + button / 2f))
            val centerY = (y0 + shiftY).coerceIn(topLimit + button / 2f, (bottomLimit - button / 2f).coerceAtLeast(topLimit + button / 2f))
            val labelLeft = (if (dockRight) centerX - button / 2f - labelGap - width else centerX + button / 2f + labelGap)
                .coerceIn(leftLimit, (rightLimit - width).coerceAtLeast(leftLimit))
            val labelTop = centerY - labelHeight / 2f
            val chipLeftF = minOf(labelLeft, centerX - button / 2f)
            val chipTopF = minOf(labelTop, centerY - button / 2f)
            val chipRightF = maxOf(labelLeft + width, centerX + button / 2f)
            val chipBottomF = maxOf(labelTop + labelHeight, centerY + button / 2f)
            val contentLeft = minOf(chipLeftF, anchorX - button / 2f)
            val contentRight = maxOf(chipRightF, anchorX + button / 2f)
            val contentTop = minOf(chipTopF, anchorY - button / 2f)
            val contentBottom = maxOf(chipBottomF, anchorY + button / 2f)
            val windowLeft = contentLeft - pad
            val windowTop = contentTop - pad
            val windowRight = contentRight + pad
            val windowBottom = contentBottom + pad
            ArcFrame(
                action = action,
                centerX = centerX,
                centerY = centerY,
                labelLeft = labelLeft,
                labelTop = labelTop,
                labelWidth = width,
                labelHeight = labelHeight,
                labelOnLeft = dockRight,
                chipLeft = chipLeftF.toInt().coerceAtLeast(0),
                chipTop = chipTopF.toInt().coerceAtLeast(0),
                chipWidth = (chipRightF - chipLeftF).toInt().coerceAtLeast(1),
                chipHeight = (chipBottomF - chipTopF).toInt().coerceAtLeast(1),
                windowLeft = windowLeft.toInt().coerceAtLeast(0),
                windowTop = windowTop.toInt().coerceAtLeast(0),
                windowWidth = (windowRight - windowLeft).toInt().coerceAtLeast(1).coerceAtMost(screenW.toInt().coerceAtLeast(1)),
                windowHeight = (windowBottom - windowTop).toInt().coerceAtLeast(1).coerceAtMost(screenH.toInt().coerceAtLeast(1)),
                fromAnchorX = anchorX - centerX,
                fromAnchorY = anchorY - centerY,
            )
        }
    }

    private fun fit(min: Float, max: Float, low: Float, high: Float): Float {
        var shift = 0f
        if (min + shift < low) shift = low - min
        if (max + shift > high) shift = high - max
        return shift
    }
}
