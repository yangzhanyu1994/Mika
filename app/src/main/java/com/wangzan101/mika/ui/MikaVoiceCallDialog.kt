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
    val permission = Manifest.permission.RECORD_AUDIO
    fun hasMicrophone() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(hasMicrophone()) }
    var micDenied by remember { mutableStateOf(false) }
    val config by runtime.settings.state.collectAsState()
    var calling by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val activeCall by rememberUpdatedState(calling)
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
    LaunchedEffect(config.credentialsConfigured, micGranted) {
        calling = config.credentialsConfigured && micGranted
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
    val needsConfig = !config.credentialsConfigured || failure == R.string.calls_setup_credentials
    val needsMic = config.credentialsConfigured && !micGranted
    val message = when {
        needsConfig -> R.string.calls_setup_credentials
        needsMic && micDenied -> R.string.mika_voice_call_mic_settings
        needsMic -> R.string.mika_voice_call_microphone
        failure != null -> failure ?: R.string.calls_action_failed
        connected -> R.string.calls_connected
        finished -> R.string.mika_voice_call_ended
        else -> R.string.calls_connecting
    }
    val buttonLabel = when {
        needsConfig -> R.string.voice_permission_open
        needsMic && micDenied -> R.string.voice_permission_open
        needsMic -> R.string.mika_voice_call_allow_mic
        calling -> R.string.calls_hangup
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
                    else -> onDismiss()
                }
            }) {
                Text(stringResource(buttonLabel))
            }
        },
        dismissButton = if (needsConfig || needsMic) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        } else null,
    )
}
