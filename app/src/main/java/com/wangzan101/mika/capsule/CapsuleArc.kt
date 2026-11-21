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

data class ArcButton(
    val action: CapsuleArcAction,
    val x: Float,
    val y: Float,
    /** Label sits on the inner side of the button so it stays on screen. */
    val labelOnLeft: Boolean,
)

/**
 * Four buttons on a quarter-arc. Right dock opens leftward with labels on the left,
 * matching the reference. Left dock mirrors. Positions are clamped inside the screen.
 * [progress] 0 is collapsed onto the capsule; 1 is fully open. The same function
 * draws the way back.
 */
object CapsuleArc {
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
        progress: Float,
        radius: Float = 168f,
        margin: Float = 12f,
    ): List<ArcButton> {
        val p = progress.coerceIn(0f, 1f)
        val direction = if (dockRight) -1f else 1f
        val span = 1.15f
        val start = -span / 2f
        return order.mapIndexed { index, action ->
            val t = if (order.size == 1) 0.5f else index.toFloat() / (order.size - 1)
            val angle = start + span * t
            var x = anchorX + direction * kotlin.math.cos(angle) * radius * p
            var y = anchorY + kotlin.math.sin(angle) * radius * p
            x = x.coerceIn(margin, (screenW - margin).coerceAtLeast(margin))
            y = y.coerceIn(margin, (screenH - margin).coerceAtLeast(margin))
            val labelOnLeft = dockRight
            ArcButton(action, x, y, labelOnLeft)
        }
    }
}
