package com.wangzan101.mika.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarMotionTest {
    @Test fun expressionsBlinkAndReturnToNeutral() {
        for ((expression, peak) in listOf(
            AvatarExpression.Thinking to 0.68f,
            AvatarExpression.Working to 0.42f,
            AvatarExpression.Done to 0.46f,
        )) {
            assertEquals(0f, blinkAmount(0f, expression), 0f)
            assertEquals(1f, blinkAmount(peak, expression), 0f)
            assertEquals(0f, blinkAmount(1f, expression), 0f)
        }
    }
}
