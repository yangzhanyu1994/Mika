package com.wangzan101.mika.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.MikaVoiceCall
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.ui.components.MikaDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun MikaVoiceCallDialog(runtime: CallRuntime, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val config by runtime.settings.state.collectAsState()
    MikaVoiceCallDialog(config.credentialsConfigured, onConfigure, onDismiss) { onConnected ->
        MikaVoiceCall(context, runtime).run(onConnected)
    }
}

private enum class CallProgress { Connecting, Connected, Ended }

@Composable
internal fun MikaVoiceCallDialog(
    credentialsConfigured: Boolean,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
    runCall: suspend (() -> Unit) -> Unit,
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
    val activeCall by rememberUpdatedState(canStart && failure == null && progress != CallProgress.Ended)
    val startCall by rememberUpdatedState(runCall)
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        micDenied = !granted
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> micGranted = hasMicrophone()
                Lifecycle.Event.ON_STOP -> if (activeCall) dismiss()
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
            startCall { progress = CallProgress.Connected }
            currentCoroutineContext().ensureActive()
            if (progress == CallProgress.Connected) progress = CallProgress.Ended
            else failure = R.string.mika_voice_call_connection_lost
        } catch (_: CancellationException) {
            // Dismissing or replacing this attempt cancels its effect. A cancellation from
            // within the call transport still needs to leave a usable retry action.
            currentCoroutineContext().ensureActive()
            failure = R.string.mika_voice_call_connection_lost
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
    val needsConfig = !credentialsConfigured || failure == R.string.calls_setup_credentials ||
        failure == R.string.mika_voice_call_realtime_key
    val needsMic = credentialsConfigured && !micGranted
    val message = when {
        needsConfig -> R.string.mika_voice_call_realtime_key
        needsMic && micDenied -> R.string.mika_voice_call_mic_settings
        needsMic -> R.string.mika_voice_call_microphone
        failure != null -> failure ?: R.string.calls_action_failed
        progress == CallProgress.Connected -> R.string.mika_voice_call_speak
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
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mika_voice_call)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = {
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
            }) {
                Text(stringResource(buttonLabel))
            }
        },
        dismissButton = if (needsConfig || needsMic || failure != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        } else null,
    )
}
