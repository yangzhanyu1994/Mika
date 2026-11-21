package com.wangzan101.mika.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MikaAssistantService : VoiceInteractionService()

class MikaAssistantSessions : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MikaAssistantSession(this)
}

/** Android's assistant descriptor requires a recognizer, including on Android 11.
 * The private, non-selectable endpoint declines platform dictation. Mika's assistant uses
 * the same VoiceInput and configured speech engine as the rest of the app.
 */
class MikaAssistantRecognizer : RecognitionService() {
    override fun onStartListening(intent: Intent, callback: Callback) = callback.error(SpeechRecognizer.ERROR_CLIENT)
    override fun onStopListening(callback: Callback) = Unit
    override fun onCancel(callback: Callback) = Unit
}

/** Temporary in-process handoff. Bitmap data never goes in an Intent or onto disk here. */
internal class AssistantCapture(val sources: Set<String>, val dismiss: () -> Unit) {
    val id: String = UUID.randomUUID().toString()
    val screenshot = MutableStateFlow<Bitmap?>(null)
    val invalidated = MutableStateFlow(false)
    @Volatile var sourceMatches = true

    fun canShare(app: MikaApp): Boolean {
        val access = app.appAccess.current()
        return sourceMatches && !invalidated.value && sources.isNotEmpty() && sources.all(access::canShare)
    }

    fun invalidate() {
        screenshot.value = null
        invalidated.value = true
        if (active === this) active = null
    }

    companion object {
        const val EXTRA_CAPTURE = "com.wangzan101.mika.assistant.capture"
        var active: AssistantCapture? = null
            internal set
    }
}

private class MikaAssistantSession(private val host: Context) : VoiceInteractionSession(host) {
    private val app get() = host.applicationContext as MikaApp
    private var capture: AssistantCapture? = null

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
        capture?.invalidate()
        capture = null
        if (host.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val sources = if (showFlags and SHOW_WITH_SCREENSHOT != 0) {
            ScreenAccessibility.instance.value?.let { runCatching { MainScreenCapture.sourcePackages(it) }.getOrNull() }.orEmpty()
        } else emptySet()
        capture = AssistantCapture(sources) { hide() }.also { AssistantCapture.active = it }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val invocation = capture ?: run { hide(); return }
        try {
            // 留在现有数字助手页里接通语音，不打开应用内通话主界面。
            startAssistantActivity(Intent(host, AssistantOverlayActivity::class.java)
                .putExtra(AssistantCapture.EXTRA_CAPTURE, invocation.id))
        } catch (e: RuntimeException) {
            Log.w("MikaAssistant", "Could not open assistant overlay", e)
            invocation.invalidate()
            hide()
        }
    }

    override fun onHandleAssist(state: AssistState) {
        val source = state.assistStructure?.activityComponent?.packageName ?: return
        val invocation = capture ?: return
        // The visible-window snapshot must agree with Android about which app was in front.
        if (source !in invocation.sources) {
            invocation.sourceMatches = false
            invocation.screenshot.value = null
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val invocation = capture ?: return
        if (!invocation.invalidated.value && invocation.canShare(app)) {
            invocation.screenshot.value = screenshot
        }
    }

    override fun onDestroy() {
        capture?.invalidate()
        capture = null
        super.onDestroy()
    }
}
