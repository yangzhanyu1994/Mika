package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
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
import com.wangzan101.mika.voice.AssistantEndpoint
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
        val session = voice.start()
        val endpoint = AssistantEndpoint(SystemClock.elapsedRealtime())
        var released = false
        var releaseAt = 0L
        try {
            coroutineScope {
                val events = Channel<SpeechEvent>(Channel.UNLIMITED)
                val collector = launch { session.events.collect { events.send(it) }; events.close() }
                while (visible) {
                    val next = withTimeoutOrNull(150) { events.receiveCatching() }
                    if (!visible) break
                    if (next?.isClosed == true) { status = VoiceCopy.NO_MATCH; break }
                    val event = next?.getOrNull()
                    val now = SystemClock.elapsedRealtime()
                    when (event) {
                        is SpeechEvent.Level -> endpoint.level(event.value, now)
                        is SpeechEvent.Partial -> { transcript = event.stable + event.tail; endpoint.partial(transcript, now) }
                        SpeechEvent.EndOfSpeech -> if (!released) { released = true; releaseAt = now; converting = true; session.release() }
                        SpeechEvent.Converting -> converting = true
                        is SpeechEvent.Final -> {
                            val text = event.text.trim()
                            if (text.isEmpty()) status = VoiceCopy.NO_MATCH else { visible = false; onText(text, sendRequested) }
                            break
                        }
                        is SpeechEvent.Failed -> { status = VoiceCopy.failure(event.why); break }
                        null -> Unit
                    }
                    if (!released && sendRequested) { released = true; releaseAt = now; converting = true; session.release() }
                    if (!released) when (endpoint.decision(now)) {
                        AssistantEndpoint.Decision.NoSpeech -> { status = VoiceCopy.NO_MATCH; break }
                        AssistantEndpoint.Decision.Finish -> { released = true; releaseAt = now; converting = true; session.release() }
                        AssistantEndpoint.Decision.Wait -> Unit
                    } else if (now - releaseAt >= 35_000) { status = VoiceCopy.NO_MATCH; break }
                }
                collector.cancel()
                events.close()
            }
        } finally {
            session.cancel()
        }
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
