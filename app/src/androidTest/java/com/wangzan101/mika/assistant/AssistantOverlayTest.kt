package com.wangzan101.mika.assistant

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssistantOverlayTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun replyReplacesWaitingStatusBelowTheSubmittedQuestion() {
        val reply = mutableStateOf(AssistantReply("正在准备", waiting = true))
        val question = "帮我看看今天下午的日程"
        val draft = mutableStateOf("")
        val pending = mutableStateOf<String?>(question)
        val resolvedMode = mutableStateOf<AssistantMode?>(null)
        val history = mutableStateOf(emptyList<AssistantExchange>())
        var sent = ""
        var opened = false
        compose.setContent {
            MikaTheme(dark = true, reduceMotion = true) {
                AssistantOverlay(
                    text = draft.value, partial = "", status = null,
                    listening = false, converting = false, level = { 0f },
                    screenshot = null, photo = null, sending = false, reply = reply.value, displayName = "小蓝",
                    pendingQuestion = pending.value, history = history.value, revealTurn = 0,
                    resolvedMode = resolvedMode.value,
                    onOpenConversation = { opened = true },
                    onText = { draft.value = it }, onEdit = {}, onVoice = {}, onScreenshot = {}, onPhoto = {},
                    onRemoveScreenshot = {}, onRemovePhoto = {}, onClear = {}, onSend = { sent = draft.value }, onClose = {},
                )
            }
        }
        compose.onNodeWithText("小蓝").assertIsDisplayed()
        compose.onNodeWithText("正在准备").assertIsDisplayed()
        compose.runOnIdle {
            resolvedMode.value = AssistantMode.Work
            reply.value = AssistantReply("正在思考", "**14:00**", running = true)
        }
        compose.onNodeWithText("自动 → 工作").assertIsDisplayed()
        compose.onNodeWithText("14:00").assertIsDisplayed()
        compose.runOnIdle {
            reply.value = AssistantReply("任务完成", "**14:00** 项目同步会")
            history.value = listOf(AssistantExchange(question, reply.value.body))
            pending.value = null
        }
        compose.onNodeWithText("正在准备").assertDoesNotExist()
        val result = compose.onNodeWithText("14:00 项目同步会").assertIsDisplayed()
        assertTrue(result.fetchSemanticsNode().boundsInRoot.top >
            compose.onNodeWithText(question).fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithText("查看完整对话").performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNode(hasSetTextAction()).performTextInput("再说一下地点")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { assertEquals("再说一下地点", sent) }
        compose.runOnIdle {
            reply.value = AssistantReply("任务完成", List(45) { "第 ${it + 1} 段回复。" }.joinToString("\n\n"))
            history.value = listOf(AssistantExchange(question, reply.value.body))
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
    }

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
