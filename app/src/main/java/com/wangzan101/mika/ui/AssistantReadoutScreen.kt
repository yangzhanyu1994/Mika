package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.assistant.AssistantReadoutSettings
import com.wangzan101.mika.assistant.ReadoutCredentials
import com.wangzan101.mika.assistant.ReadoutModel
import com.wangzan101.mika.assistant.ReadoutProvider
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantReadoutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AssistantReadoutSettings(context) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(settings.model()) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var provider by remember { mutableStateOf(saved.provider) }
    var model by remember(provider) { mutableStateOf(if (provider == saved.provider) saved.model else if (provider == ReadoutProvider.Doubao) ReadoutModel().model else "gpt-4o-mini-tts") }
    var voice by remember(provider) { mutableStateOf(if (provider == saved.provider) saved.voice
        else if (provider == ReadoutProvider.Doubao) ReadoutModel().voice else "alloy") }
    var url by remember(provider) { mutableStateOf(if (provider == saved.provider) saved.baseUrl else "https://api.openai.com/v1") }
    var key by remember(provider) { mutableStateOf("") }
    var legacy by remember(provider) { mutableStateOf(false) }
    var appId by remember(provider) { mutableStateOf("") }
    var accessKey by remember(provider) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    val doubao = provider == ReadoutProvider.Doubao

    PageScaffold(stringResource(R.string.assistant_readout_settings), onBack) {
        GroupCard {
            ListRow(null, stringResource(R.string.assistant_readout_enable), first = true) {
                MikaSwitch(enabled, onCheckedChange = { checked ->
                    if (!busy) {
                        busy = true
                        scope.launch {
                            if (withContext(Dispatchers.IO) { settings.setEnabled(checked) }) enabled = checked
                            else feedback = R.string.assistant_readout_save_error
                            busy = false
                        }
                    }
                })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectChip("豆包 / Doubao", doubao, { if (!busy) provider = ReadoutProvider.Doubao })
                SelectChip(stringResource(R.string.assistant_readout_compatible), !doubao, {
                    if (!busy) provider = ReadoutProvider.Compatible
                })
            }
            if (doubao) {
                Text(stringResource(R.string.assistant_readout_doubao))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectChip("API Key", !legacy, { if (!busy) legacy = false })
                    SelectChip(stringResource(R.string.speech_doubao_legacy), legacy, { if (!busy) legacy = true })
                }
            } else {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.model_base_url)) })
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.assistant_readout_model)) })
            }
            OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.assistant_readout_voice)) })
            if (doubao && legacy) {
                OutlinedTextField(appId, { appId = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text("App ID") })
                OutlinedTextField(accessKey, { accessKey = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("Access Key") })
            } else {
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("API Key") })
            }
            ModelKeyWebsite(if (doubao) DOUBAO_VOICE_KEY_GUIDE else modelKeyWebsite(url))
            FooterNote(stringResource(R.string.assistant_readout_privacy))
            MikaButton(stringResource(R.string.save), onClick = {
                val configuration = if (doubao) ReadoutModel(voice = voice.trim())
                else ReadoutModel(provider, model.trim(), voice.trim(), url.trim())
                val replacement = if (doubao && legacy) {
                    if (appId.isBlank() && accessKey.isBlank()) null else ReadoutCredentials("", appId.trim(), accessKey.trim())
                } else key.trim().takeIf(String::isNotEmpty)?.let { ReadoutCredentials(it) }
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { settings.save(configuration, replacement) }.isSuccess }
                    busy = false
                    feedback = if (ok) R.string.assistant_readout_saved else R.string.assistant_readout_save_error
                    if (ok) {
                        saved = configuration
                        key = ""
                        appId = ""
                        accessKey = ""
                    }
                }
            }, enabled = !busy)
            feedback?.let { Text(stringResource(it), color = if (it == R.string.assistant_readout_saved) MikaTheme.colors.ok else MikaTheme.colors.bad) }
        }
    }
}
