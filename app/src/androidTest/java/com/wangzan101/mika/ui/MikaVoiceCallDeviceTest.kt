package com.wangzan101.mika.ui

import android.Manifest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MikaVoiceCallDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsOnceWithGrantedMicrophoneAndRetriesAnInterruptedAttempt() {
        val instruments = InstrumentationRegistry.getInstrumentation()
        instruments.uiAutomation.grantRuntimePermission(instruments.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        val visible = mutableStateOf(true)
        val starts = AtomicInteger()
        val interruptFirst = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        compose.activityRule.scenario.onActivity { host ->
            host.setContentView(ComposeView(host).apply {
                setContent {
                    MikaTheme {
                        if (visible.value) {
                            MikaVoiceCallDialog(
                                credentialsConfigured = true,
                                onConfigure = { error("Test credentials are already configured") },
                                onDismiss = { visible.value = false },
                            ) { onConnected ->
                                if (starts.incrementAndGet() == 1) {
                                    interruptFirst.await()
                                    throw CancellationException("Scripted call interruption")
                                }
                                onConnected()
                                try {
                                    awaitCancellation()
                                } finally {
                                    secondStopped.complete(Unit)
                                }
                            }
                        }
                    }
                }
            })
        }
        fun label(id: Int) = compose.activity.getString(id)
        compose.waitUntil(5_000) { starts.get() == 1 }
        compose.onNodeWithText(label(R.string.calls_connecting)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.done)).assertDoesNotExist()

        interruptFirst.complete(Unit)
        compose.onNodeWithText(label(R.string.mika_voice_call_connection_lost)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.retry)).performClick()
        compose.waitUntil(5_000) { starts.get() == 2 }
        compose.onNodeWithText(label(R.string.mika_voice_call_speak)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.calls_hangup)).performClick()
        compose.waitUntil(5_000) { secondStopped.isCompleted }
        compose.onNodeWithText(label(R.string.mika_voice_call)).assertDoesNotExist()
        assertEquals(2, starts.get())
    }
}
