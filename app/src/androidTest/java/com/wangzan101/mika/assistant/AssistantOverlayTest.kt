package com.wangzan101.mika.assistant

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class AssistantOverlayTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun previewAndRemoveMediaWithoutSendingTheVoiceDraft() {
        val screen = mutableStateOf<Bitmap?>(null)
        val photo = mutableStateOf<Bitmap?>(null)
        val transcript = mutableStateOf("")
        val bitmap = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
        var sends = 0
        compose.setContent {
            MikaTheme(dark = true, reduceMotion = true) {
                AssistantOverlay(
                    text = "", partial = transcript.value, status = null,
                    listening = true, converting = false, level = { 0.5f },
                    screenshot = screen.value, photo = photo.value, sending = false,
                    onText = {}, onEdit = {}, onVoice = {}, onScreenshot = { screen.value = bitmap },
                    onPhoto = { photo.value = bitmap }, onRemoveScreenshot = { screen.value = null },
                    onRemovePhoto = { photo.value = null }, onClear = {}, onSend = { sends++ }, onClose = {},
                )
            }
        }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.onNodeWithContentDescription("附上当前画面").performClick()
        compose.onNodeWithContentDescription("相机").performClick()
        compose.onNodeWithContentDescription("截图预览").performClick()
        compose.onNodeWithText("截图预览").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("去掉截图预览").performClick()
        compose.runOnIdle {
            transcript.value = "请帮我看看"
            assertNotNull(photo.value)
            assertEquals(0, sends)
        }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }
}
