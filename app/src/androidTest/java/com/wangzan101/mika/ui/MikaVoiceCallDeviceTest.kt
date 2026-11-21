package com.wangzan101.mika.ui

import android.Manifest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.MikaVoiceCall
import com.wangzan101.mika.calls.VoiceCallLevels
import com.wangzan101.mika.calls.VoiceCallSpeakerSample
import com.wangzan101.mika.ui.theme.MikaTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
        val speak = CompletableDeferred<Unit>()
        val speaker = MutableStateFlow<VoiceCallSpeakerSample?>(null)
        val work = mutableStateOf<VoiceCallProgress?>(null)
        compose.activityRule.scenario.onActivity { host ->
            host.setContentView(ComposeView(host).apply {
                setContent {
                    MikaTheme(reduceMotion = true) {
                        if (visible.value) {
                            MikaVoiceCallDialog(
                                credentialsConfigured = true,
                                onConfigure = { error("Test credentials are already configured") },
                                onDismiss = { visible.value = false },
                                work = work.value,
                            ) { onConnected ->
                                if (starts.incrementAndGet() == 1) {
                                    interruptFirst.await()
                                    throw CancellationException("Scripted call interruption")
                                }
                                onConnected(VoiceCallLevels(MutableStateFlow(0f), speaker))
                                try {
                                    speak.await()
                                    var sequence = 0L
                                    while (true) {
                                        speaker.value = VoiceCallSpeakerSample(0.65f, ++sequence)
                                        delay(50)
                                    }
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
        compose.onNodeWithTag("mika-call-timer").assertIsDisplayed()
        compose.runOnIdle { work.value = VoiceCallProgress(OrbMotion.Searching) }
        compose.onNodeWithText(label(R.string.assistant_searching)).assertIsDisplayed()
        compose.runOnIdle { work.value = VoiceCallProgress(OrbMotion.Working, "Test MCP") }
        compose.onNodeWithText(compose.activity.getString(R.string.progress_calling_mcp, "Test MCP")).assertIsDisplayed()
        speak.complete(Unit)
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(label(R.string.mika_voice_call_mika_speaking)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.calls_hangup)).performClick()
        compose.waitUntil(5_000) { secondStopped.isCompleted }
        compose.onNodeWithText(label(R.string.mika_voice_call)).assertDoesNotExist()
        assertEquals(2, starts.get())
    }

    @Test fun reachingTheCallLimitShowsTheReasonAndCloses() {
        val instruments = InstrumentationRegistry.getInstrumentation()
        instruments.uiAutomation.grantRuntimePermission(instruments.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        val visible = mutableStateOf(true)
        val reachLimit = CompletableDeferred<Unit>()
        compose.activityRule.scenario.onActivity { host ->
            host.setContentView(ComposeView(host).apply {
                setContent {
                    MikaTheme(reduceMotion = true) {
                        if (visible.value) {
                            MikaVoiceCallDialog(
                                credentialsConfigured = true,
                                onConfigure = { error("Test credentials are already configured") },
                                onDismiss = { visible.value = false },
                            ) { onConnected ->
                                onConnected(VoiceCallLevels(MutableStateFlow(0f), MutableStateFlow(null)))
                                reachLimit.await()
                                throw MikaVoiceCall.TimeLimitReached()
                            }
                        }
                    }
                }
            })
        }
        compose.onNodeWithTag("mika-call-timer").assertIsDisplayed()
        reachLimit.complete(Unit)
        val limit = compose.activity.getString(R.string.mika_voice_call_time_limit)
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(limit)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(limit).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.onNodeWithText(limit).assertDoesNotExist()
    }
}
