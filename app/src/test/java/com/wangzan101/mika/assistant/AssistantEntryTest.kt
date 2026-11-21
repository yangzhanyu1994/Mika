package com.wangzan101.mika.assistant

import com.wangzan101.mika.ui.MainActivity
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AssistantEntryTest {
    @Test fun theSystemAssistantPageIsNotTheFullCallScreen() {
        assertNotEquals(MainActivity.EXTRA_OPEN_VOICE_CALL, AssistantCapture.EXTRA_CAPTURE)
    }
}
