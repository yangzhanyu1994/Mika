package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.VoiceSource
import com.wangzan101.mika.voice.assistantSpeech

/** One opt-in invocation from the system assistant gesture. */
@Composable
internal fun AssistantVoiceCapture(request: Int, voice: VoiceSource, onText: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }
    var transcript by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var converting by remember { mutableStateOf(false) }
    var sendRequested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { visible = true; run++ } else { visible = true; status = context.getString(R.string.voice_permission_title) }
    }
    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        transcript = ""
        status = null
        converting = false
        sendRequested = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            visible = true
            run++
        } else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LifecycleStartEffect(Unit) { onStopOrDispose { visible = false } }
    LaunchedEffect(run, visible) {
        if (!visible || run == 0 || status != null) return@LaunchedEffect
        assistantSpeech(voice, { sendRequested }) { event ->
            when (event) {
                is SpeechEvent.Partial -> transcript = event.stable + event.tail
                SpeechEvent.Converting -> converting = true
                is SpeechEvent.Final -> {
                    val text = event.text.trim()
                    if (text.isEmpty()) status = VoiceCopy.NO_MATCH
                    else { visible = false; onText(text, sendRequested) }
                }
                is SpeechEvent.Failed -> status = VoiceCopy.failure(event.why)
                else -> Unit
            }
        }
        if (visible && status == null) status = VoiceCopy.NO_MATCH
    }
    if (visible) AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(stringResource(if (converting) R.string.voice_converting else R.string.voice_listening)) },
        text = { Text(status ?: if (transcript.isBlank()) stringResource(R.string.voice_say_now) else transcript) },
        confirmButton = {
            if (status == null) TextButton(onClick = { sendRequested = true }, enabled = !sendRequested) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = { TextButton(onClick = { visible = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
