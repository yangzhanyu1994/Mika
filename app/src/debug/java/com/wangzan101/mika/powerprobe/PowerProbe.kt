package com.wangzan101.mika.powerprobe

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var helperTestRunning = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timeout = Runnable {
        if (waiting) {
            waiting = false
            label?.text = "系统助理小窗：8 秒内未收到电源键松开事件"
        }
    }

    override fun onCreateContentView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // The assistant window can extend under the status bar. Keep the result below it.
        val dp = context.resources.displayMetrics.density
        setPadding((20 * dp).toInt(), (100 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        label = TextView(context).also {
            it.text = "系统助理小窗：请松开电源键"
            it.textSize = 20f
            it.setTextColor(Color.BLACK)
            it.setBackgroundColor(Color.WHITE)
            it.setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(Button(context).apply {
            text = "用手机助手检测电源键"
            setOnClickListener { testWithHelper() }
        })
        addView(Button(context).apply { text = "关闭"; setOnClickListener { finish() } })
    }

    private fun testWithHelper() {
        if (helperTestRunning) return
        val shell = PhoneServices.of(context).hub.shell.value
        if (shell == null) {
            label?.text = "测试版手机助手未连接。请先在 Mika Dev 配置并启动手机助手，再回来测试。"
            return
        }
        waiting = false
        handler.removeCallbacks(timeout)
        helperTestRunning = true
        label?.text = "请在 8 秒内长按电源键，再松开；只报告电源键结果，不保存按键数据。"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { shell.runCommand(POWER_UP_TEST)?.getString("output").orEmpty() }.getOrDefault("")
            }
            helperTestRunning = false
            val lines = result.lineSequence().map { it.trim() }.toSet()
            label?.text = when {
                "POWER_UP" in lines -> "手机助手：收到了电源键松开事件"
                "NO_DEVICE" in lines -> "手机助手无法访问电源键设备"
                else -> "手机助手：8 秒内未检测到电源键松开事件"
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (helperTestRunning) return
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

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // Read capabilities to identify the power-key device; during the bounded test,
        // only KEY_POWER release is allowed through the pipe. No events are saved or logged.
        private const val POWER_UP_TEST = """
            d=${'$'}(getevent -lp 2>/dev/null | awk '/add device/ {d=${'$'}4} /KEY_POWER/ {print d; exit}')
            case "${'$'}d" in /dev/input/event[0-9]*) ;; *) echo NO_DEVICE; exit ;; esac
            if timeout 8 getevent -l "${'$'}d" 2>/dev/null | grep -q -m 1 'KEY_POWER.*UP'; then echo POWER_UP; else echo NO_POWER_UP; fi
        """
    }
}
