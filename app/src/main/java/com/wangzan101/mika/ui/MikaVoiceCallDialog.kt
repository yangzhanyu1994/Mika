package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.MikaVoiceCall
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.ui.components.MikaDialog
import kotlinx.coroutines.CancellationException

@Composable
internal fun MikaVoiceCallDialog(runtime: CallRuntime, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    val config by runtime.settings.state.collectAsState()
    var calling by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) calling = true
        else if (!granted) failure = R.string.voice_permission_title
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dismiss() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(config.credentialsConfigured) {
        if (!config.credentialsConfigured) {
            calling = false
        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            calling = true
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(calling) {
        if (!calling) return@LaunchedEffect
        try {
            MikaVoiceCall(context, runtime).run { connected = true }
            finished = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: MikaVoiceCall.Failure) {
            failure = e.messageId
        } catch (e: VolcRealtimeException) {
            failure = if (e.kind == VolcRealtimeException.Kind.AUTH) R.string.calls_setup_credentials else R.string.calls_action_failed
        } catch (_: Exception) {
            failure = R.string.calls_action_failed
        } finally {
            connected = false
            calling = false
        }
    }
    val message = when {
        !config.credentialsConfigured -> R.string.calls_setup_credentials
        failure != null -> failure ?: R.string.calls_action_failed
        connected -> R.string.calls_connected
        calling -> R.string.calls_connecting
        finished -> R.string.mika_voice_call_ended
        else -> R.string.voice_permission_title
    }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mika_voice_call)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            val needsConfig = !config.credentialsConfigured || failure == R.string.calls_setup_credentials
            TextButton(onClick = if (needsConfig) onConfigure else onDismiss) {
                Text(stringResource(if (needsConfig) R.string.voice_permission_open else if (calling) R.string.calls_hangup else R.string.done))
            }
        },
        dismissButton = if (!config.credentialsConfigured || failure == R.string.calls_setup_credentials) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        } else null,
    )
}
