package com.wangzan101.mika.powerprobe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Diagnostic only: no speech recognition, screen capture, event storage or network calls. */
class ProbeVoiceService : VoiceInteractionService()

class ProbeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PowerProbeSession(this)
}

class ProbeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onStopListening(listener: Callback?) = Unit
    override fun onCancel(listener: Callback?) = Unit
}

private class PowerProbeSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var label: TextView? = null
    private var waiting = false
    private val timeout = Runnable {
        if (waiting) {
            waiting = false
            label?.text = "系统助理小窗：8 秒内未收到电源键松开事件"
        }
    }

    override fun onCreateContentView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(36, 28, 36, 28)
        label = TextView(context).also { it.text = "系统助理小窗：请松开电源键"; addView(it) }
        addView(Button(context).apply { text = "关闭"; setOnClickListener { finish() } })
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        waiting = true
        label?.text = "系统助理小窗：请松开电源键"
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 8_000L)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (waiting && keyCode == KeyEvent.KEYCODE_POWER) {
            waiting = false
            handler.removeCallbacks(timeout)
            label?.text = "系统助理小窗：收到了电源键松开事件"
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onHide() {
        waiting = false
        handler.removeCallbacks(timeout)
        super.onHide()
    }
}
