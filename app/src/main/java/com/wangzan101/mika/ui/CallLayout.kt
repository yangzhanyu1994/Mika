package com.wangzan101.mika.ui

/**
 * Call-page spacing in dp. 1 inch = 160 dp, so 2 cm is 160 / 2.54 * 2.
 * The hang-up control sits that far above the navigation / gesture inset when the
 * screen can spare a sphere. Short covers and large font scales keep a smaller lift
 * so the button stays on screen. Physical distance is calibrated on a Samsung cover later.
 */
internal object CallLayout {
    const val DP_PER_CM = 160f / 2.54f
    const val HANGUP_LIFT_DP = 2f * DP_PER_CM
    const val HEADER_DROP_DP = 18f
    const val MIN_ORB_DP = 148f
    const val MIN_LIFT_DP = 24f

    fun hangupLiftDp(screenHeightDp: Float, navigationInsetDp: Float, headerDp: Float, buttonDp: Float): Float {
        val room = screenHeightDp - navigationInsetDp - headerDp - buttonDp - MIN_ORB_DP
        return HANGUP_LIFT_DP.coerceAtMost(room.coerceAtLeast(MIN_LIFT_DP)).coerceAtLeast(MIN_LIFT_DP)
    }

    /** Extra drop under the status inset. Font scale moves text, not this dp gap. */
    fun headerDropDp(fontScale: Float): Float = HEADER_DROP_DP * fontScale.coerceIn(1f, 1.15f)
}
