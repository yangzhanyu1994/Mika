package com.wangzan101.mika.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.chat.DeleteImpact
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteDialogTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingEdgeOfVisibleDeleteButtonConfirms() {
        var confirmed = false
        compose.setContent {
            DeleteConversationDialog("测试对话", DeleteImpact(false, 0), onConfirm = { confirmed = true }, onDismiss = {})
        }
        compose.onNodeWithTag("confirm-delete-conversation").performTouchInput {
            click(Offset(center.x * 0.16f, center.y))
        }
        compose.runOnIdle { assertTrue(confirmed) }
    }
}
