package com.wangzan101.mika.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.MikaVoiceCall
import com.wangzan101.mika.calls.MikaVoiceCallService
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.calls.VoiceCallLevels
import com.wangzan101.mika.calls.VoiceSpeechPhase
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.calls.CALL_GREETING
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun MikaVoiceCallDialog(
    runtime: CallRuntime,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
    saveTranscript: suspend (List<Turn>) -> Unit,
    greet: Boolean = true,
    onGreeted: () -> Unit = {},
    delegate: suspend (String, (VoiceCallProgress) -> Unit) -> String,
) {
    val context = LocalContext.current
    val config by runtime.settings.state.collectAsState()
    var work by remember { mutableStateOf<VoiceCallProgress?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val typed = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    val handle by rememberUpdatedState(delegate)
    fun show(progress: VoiceCallProgress) {
        work = progress
        val line = progress.status ?: return
        if (lines.lastOrNull() != line) lines = (lines + line).takeLast(12)
    }
    MikaVoiceCallDialog(
        config.credentialsConfigured, onConfigure, onDismiss, work, lines, keepInBackground = true,
        onType = { text -> typed.tryEmit(text) },
    ) { onConnected ->
        work = null
        MikaVoiceCallService.withMicrophone(context.applicationContext) {
            MikaVoiceCall(context, runtime).run(onConnected, saveTranscript, greet = greet, onGreeted = onGreeted, typed = typed) { words ->
                show(VoiceCallProgress(OrbMotion.Thinking, status = context.getString(R.string.mika_voice_call_status_working)))
                try {
                    handle(words) { show(it) }
                } finally {
                    if (work?.motion != OrbMotion.Complete && work?.needsTaskView != true) work = null
                }
            }
        }
    }
}

private enum class CallProgress { Connecting, Connected, Ended }

@Composable
internal fun MikaVoiceCallDialog(
    credentialsConfigured: Boolean,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
    work: VoiceCallProgress? = null,
    lines: List<String> = emptyList(),
    keepInBackground: Boolean = false,
    onType: (String) -> Unit = {},
    runCall: suspend ((VoiceCallLevels) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    val permission = Manifest.permission.RECORD_AUDIO
    fun hasMicrophone() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(hasMicrophone()) }
    var micDenied by remember { mutableStateOf(false) }
    val canStart = credentialsConfigured && micGranted
    var attempt by remember { mutableIntStateOf(0) }
    var progress by remember(canStart, attempt, lifecycle) { mutableStateOf(CallProgress.Connecting) }
    var failure by remember(canStart, attempt, lifecycle) { mutableStateOf<Int?>(null) }
    var endedAtLimit by remember(canStart, attempt, lifecycle) { mutableStateOf(false) }
    var levels by remember(attempt, canStart) { mutableStateOf<VoiceCallLevels?>(null) }
    var connectedAt by remember(attempt, canStart) { mutableStateOf<Long?>(null) }
    var seconds by remember(attempt, canStart) { mutableLongStateOf(0L) }
    var speaking by remember(attempt, canStart) { mutableStateOf(false) }
    var output by remember(attempt, canStart) { mutableFloatStateOf(0f) }
    val active = canStart && failure == null && progress != CallProgress.Ended
    val connected = active && progress == CallProgress.Connected
    val activeCall by rememberUpdatedState(active)
    val input = levels?.microphone?.collectAsState()
    val speech = levels?.speech?.collectAsState()?.value ?: VoiceSpeechPhase.Idle
    LaunchedEffect(connected, connectedAt) {
        val start = connectedAt ?: return@LaunchedEffect
        while (connected) {
            val elapsed = SystemClock.elapsedRealtime() - start
            seconds = elapsed / 1_000
            delay(1_000 - elapsed % 1_000)
        }
    }
    LaunchedEffect(levels, connected) {
        val meter = levels ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        meter.speaker.collectLatest { sample ->
            output = sample?.level ?: 0f
            if (sample == null) {
                speaking = false
            } else {
                if (sample.level >= 0.035f) speaking = true
                delay(180)
                speaking = false
                output = 0f
            }
        }
    }
    val startCall by rememberUpdatedState(runCall)
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        micDenied = !granted
    }
    DisposableEffect(lifecycle, keepInBackground) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> micGranted = hasMicrophone()
                Lifecycle.Event.ON_STOP -> if (activeCall && !keepInBackground) dismiss()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(canStart, attempt, lifecycle) {
        if (!canStart) return@LaunchedEffect
        try {
            if (withTimeoutOrNull(3_000) { lifecycle.withResumed { true } } != true) {
                failure = R.string.mika_voice_call_foreground
                return@LaunchedEffect
            }
            startCall { meters ->
                levels = meters
                connectedAt = SystemClock.elapsedRealtime()
                progress = CallProgress.Connected
            }
            currentCoroutineContext().ensureActive()
            if (progress == CallProgress.Connected) progress = CallProgress.Ended
            else failure = R.string.mika_voice_call_connection_lost
        } catch (_: CancellationException) {
            // Dismissing or replacing this attempt cancels its effect. A cancellation from
            // within the call transport still needs to leave a usable retry action.
            currentCoroutineContext().ensureActive()
            failure = R.string.mika_voice_call_connection_lost
        } catch (_: MikaVoiceCallService.HungUp) {
            dismiss()
        } catch (_: MikaVoiceCall.TimeLimitReached) {
            endedAtLimit = true
            progress = CallProgress.Ended
        } catch (e: MikaVoiceCall.Failure) {
            failure = e.messageId
        } catch (e: VolcRealtimeException) {
            failure = when (e.kind) {
                VolcRealtimeException.Kind.AUTH -> R.string.mika_voice_call_realtime_key
                VolcRealtimeException.Kind.TIMEOUT -> R.string.mika_voice_call_connection_timeout
                VolcRealtimeException.Kind.NETWORK -> R.string.mika_voice_call_connection_lost
                VolcRealtimeException.Kind.RATE_LIMITED -> R.string.voice_test_quota
                else -> R.string.mika_voice_call_provider_error
            }
        } catch (_: Exception) {
            failure = R.string.calls_action_failed
        }
    }
    var speakerOn by remember { mutableStateOf(true) }
    val audioSession = remember(context) {
        context.getSystemService(AudioManager::class.java)?.let { com.wangzan101.mika.calls.CallAudioSession(it) }
    }
    DisposableEffect(audioSession) {
        audioSession?.setListener { speakerOn = it.speaker }
        speakerOn = audioSession?.start(preferSpeaker = true)?.speaker ?: true
        onDispose { audioSession?.restore() }
    }
    val needsConfig = !credentialsConfigured || failure == R.string.calls_setup_credentials ||
        failure == R.string.mika_voice_call_realtime_key
    val needsMic = credentialsConfigured && !micGranted
    val motion = when {
        !connected -> OrbMotion.Connecting
        speaking -> OrbMotion.Speaking
        speech == VoiceSpeechPhase.Listening -> OrbMotion.Listening
        work != null -> work.motion
        speech == VoiceSpeechPhase.Thinking -> OrbMotion.Thinking
        speech == VoiceSpeechPhase.Writing -> OrbMotion.Writing
        else -> OrbMotion.Idle
    }
    val message = when {
        needsConfig -> R.string.mika_voice_call_realtime_key
        needsMic && micDenied -> R.string.mika_voice_call_mic_settings
        needsMic -> R.string.mika_voice_call_microphone
        failure != null -> failure ?: R.string.calls_action_failed
        connected -> motion.callLabel
        endedAtLimit -> R.string.mika_voice_call_time_limit
        progress == CallProgress.Ended -> R.string.mika_voice_call_ended
        else -> R.string.calls_connecting
    }
    val buttonLabel = when {
        needsConfig -> R.string.voice_permission_open
        needsMic && micDenied -> R.string.voice_permission_open
        needsMic -> R.string.mika_voice_call_allow_mic
        failure != null -> R.string.retry
        progress == CallProgress.Connecting -> R.string.cancel
        progress == CallProgress.Connected -> R.string.calls_hangup
        else -> R.string.done
    }
    MikaVoiceCallOverlay(
        active = active,
        connected = connected,
        seconds = seconds,
        motion = motion,
        connector = work?.connector?.takeIf { connected && work.motion == motion },
        needsTaskView = connected && work?.needsTaskView == true,
        input = input?.value ?: 0f,
        output = output,
        message = message,
        action = buttonLabel,
        showCancel = needsConfig || needsMic || failure != null,
        lines = lines,
        onType = onType,
        speakerOn = speakerOn,
        onSpeaker = {
            speakerOn = audioSession?.toggle()?.speaker ?: !speakerOn
        },
        onAction = {
            when {
                needsConfig -> onConfigure()
                needsMic && micDenied -> {
                    val appUri = Uri.fromParts("package", context.packageName, null)
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))
                }
                needsMic -> microphone.launch(permission)
                failure != null -> attempt++
                else -> onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}
