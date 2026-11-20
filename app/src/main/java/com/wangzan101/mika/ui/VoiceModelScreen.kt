package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceProviderPreset
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val PREVIEW_TIMEOUT_MS = 20_000L

private data class VoiceSetup(val label: String, val preset: VoiceProviderPreset, val provider: Provider?)

@Composable
internal fun VoiceModelScreen(app: MikaApp, onBack: () -> Unit, onCallModel: () -> Unit) {
    val settings = app.voiceSettings
    val store = app.providers
    val state by settings.state.collectAsState()
    val calls by app.calls.settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val keyRevisions by store.providerKeyRevisions.collectAsState()
    val player = remember(app) { VoicePlayer(app) }
    val playback by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val doubaoLabel = stringResource(R.string.voice_doubao_tts)
    val keyboard = LocalSoftwareKeyboardController.current
    val sample = stringResource(R.string.voice_sample_text)
    var setup by remember { mutableStateOf<VoiceSetup?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var setupStatus by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    val activeProvider = providers.firstOrNull { it.id == state.choice?.providerId }
    val presetProviders = remember(providers, keyRevisions, activeProvider) {
        VoiceProviderPreset.entries.associateWith { preset ->
            val official = providers.filter { preset.matches(it) && it.signIn == null }
            official.firstOrNull { it.id == activeProvider?.id && store.hasKey(it.id) }
                ?: official.firstOrNull { it.name == preset.storedLabel && store.hasKey(it.id) }
                ?: official.firstOrNull { store.hasKey(it.id) }
        }
    }
    val ready = state.hasModel(calls.credentialsConfigured) && (state.useDoubao || activeProvider != null)
    DisposableEffect(player) { onDispose { player.stop() } }

    fun openSetup(row: VoiceSetup) {
        if (busy) return
        player.stop()
        testStatus = null
        setupStatus = null
        apiKey = ""
        setup = row
    }

    fun closeSetup() {
        setup = null
        apiKey = ""
        setupStatus = null
    }

    fun cancelSetup() {
        requestJob?.cancel()
        closeSetup()
    }

    fun testFailure(error: Exception): String {
        val credentials = error is ModelError.Auth ||
            (error is VolcRealtimeException && error.kind == VolcRealtimeException.Kind.AUTH)
        val reason = when {
            error is TimeoutCancellationException -> tr(R.string.voice_test_timeout)
            credentials || error is ModelError.ModelNotAvailable || error is ModelError.IllegalHeader -> tr(R.string.voice_invalid_key)
            error is ModelError.Network || error is ModelError.Proxy -> tr(R.string.voice_test_network)
            error is ModelError.RateLimited || error is ModelError.QuotaExceeded -> tr(R.string.voice_test_quota)
            error is ModelError.BadResponse && error.message.orEmpty().contains("Doubao TTS code") ->
                tr(R.string.voice_doubao_tts_error, error.message.orEmpty().substringAfterLast(' '))
            else -> tr(R.string.retry)
        }
        return tr(R.string.voice_test_failed, reason)
    }

    Page(tr(R.string.role_voice), onBack) {
        GroupLabel(tr(R.string.voice_provider), Modifier.padding(top = 6.dp))
        GroupCard {
            VoiceProviderPreset.entries.forEachIndexed { index, preset ->
                val label = if (preset == VoiceProviderPreset.Doubao) doubaoLabel else preset.label
                val selected = !state.useDoubao && state.choice?.protocol == preset.protocol &&
                    activeProvider?.let(preset::matches) == true
                RadioRow(label, selected, first = index == 0, enabled = !busy,
                    onClick = { openSetup(VoiceSetup(label, preset, presetProviders[preset])) })
            }
            if (state.useDoubao) {
                ListRow(null, tr(R.string.assistant_readout_doubao), tr(R.string.voice_doubao_shared),
                    onClick = onCallModel) { RowChevron() }
            } else if (activeProvider != null && VoiceProviderPreset.entries.none { it.matches(activeProvider) }) {
                ListRow(null, ModelsCopy.providerName(activeProvider), state.choice?.model)
            }
        }

        val playing = playback == VoicePlayback.Playing || playback == VoicePlayback.Loading
        val testing = busy && setup == null
        MikaButton(
            tr(when {
                testing -> R.string.cancel
                playing -> R.string.terminal_stop
                else -> R.string.voice_test
            }),
            onClick = {
                when {
                    testing -> requestJob?.cancel()
                    playing -> player.stop()
                    else -> {
                        testStatus = null
                        busy = true
                        requestJob = scope.launch {
                            try {
                                var played = false
                                withTimeout(PREVIEW_TIMEOUT_MS) {
                                    app.readReply(sample, preview = true) {
                                        player.play(it.bytes, it.mime)
                                        played = true
                                    }
                                }
                                if (!played) testStatus = tr(R.string.calls_key_missing)
                            } catch (error: TimeoutCancellationException) {
                                testStatus = testFailure(error)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                testStatus = testFailure(error)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            },
            enabled = playing || testing || (ready && !busy),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        val status = when (val result = playback) {
            VoicePlayback.Playing -> tr(R.string.voice_playing)
            VoicePlayback.Loading -> tr(R.string.voice_loading)
            is VoicePlayback.Error -> tr(R.string.voice_play_failed, result.message)
            VoicePlayback.Idle -> if (testing) tr(R.string.voice_testing) else testStatus
        }
        status?.let { FooterNote(it, Modifier.padding(vertical = 6.dp)) }

        GroupCard {
            ListRow(null, tr(R.string.voice_enable), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) })
            ListRow(null, tr(R.string.voice_auto_read),
                trailing = { MikaSwitch(state.autoRead, settings::setAutoRead, enabled = state.enabled) })
        }
        FooterNote(tr(R.string.voice_hint))

        GroupCard {
            ListRow(null, tr(R.string.calls_advanced), first = true, onClick = { advanced = !advanced }) { RowChevron() }
        }
        if (advanced) {
            GroupLabel(tr(R.string.voice_task_audio))
            GroupCard {
                ListRow(null, tr(R.string.voice_speak_tool), tr(R.string.voice_speak_tool_detail), first = true,
                    trailing = { MikaSwitch(state.speakTool, settings::setSpeakTool) })
                ListRow(null, tr(R.string.voice_save_enabled), tr(R.string.voice_save_note),
                    trailing = { MikaSwitch(state.saveEnabled, settings::setSaveEnabled) })
                ListRow(null, tr(R.string.voice_ask_before_save),
                    trailing = { MikaSwitch(state.askBeforeSave, settings::setAskBeforeSave, enabled = state.saveEnabled) })
                InputRow(tr(R.string.voice_max_chars), state.maxCharsPerSpeak.toString(),
                    onChange = { it.toIntOrNull()?.let(settings::setMaxChars) }, placeholder = "2000", keyboard = KeyboardType.Number)
                FooterNote(tr(R.string.voice_char_limit_detail), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            GroupCard {
                ListRow(null, tr(R.string.voice_custom_provider), tr(R.string.voice_custom_provider_detail),
                    first = true, onClick = { custom = !custom }) { RowChevron() }
            }
            if (custom) VoiceCustomSettings(providers, state.choice.takeUnless { state.useDoubao }, settings::setChoice, busy)
        }
    }

    setup?.let { target ->
        val preset = target.preset
        val hasSavedKey = target.provider != null
        MikaDialog(
            onDismissRequest = ::cancelSetup,
            title = { Text(target.label) },
            text = {
                Column {
                    if (preset == VoiceProviderPreset.Doubao) FooterNote(tr(R.string.voice_doubao_tts_setup))
                    InputRow(ModelSetupCopy.API_KEY, apiKey, { if (!busy) apiKey = it }, "",
                        first = true, keyboard = KeyboardType.Password, secret = true)
                    if (hasSavedKey) FooterNote(tr(R.string.voice_key_reuse))
                    ModelKeyWebsite(preset.keyWebsite)
                    setupStatus?.let { FooterNote(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && (apiKey.isNotBlank() || hasSavedKey), onClick = {
                    val replacement = apiKey.trim()
                    val maxChars = maxOf(state.maxCharsPerSpeak, sample.length)
                    keyboard?.hide()
                    busy = true
                    setupStatus = tr(R.string.voice_testing)
                    requestJob = scope.launch {
                        try {
                            val audio = withTimeout(PREVIEW_TIMEOUT_MS) {
                                withContext(Dispatchers.IO) {
                                    val key = replacement.ifEmpty { target.provider?.let { store.providerKey(it.id) }.orEmpty() }
                                    val model = store.voicePresetModel(preset, key, maxChars)
                                    model.synthesize(TtsRequest(sample.take(model.capabilities.maxChars), preset.voice.ifBlank { null }))
                                }
                            }
                            val provider = withContext(Dispatchers.IO) {
                                if (replacement.isEmpty()) checkNotNull(target.provider)
                                else store.connect(preset.storedLabel, preset.kind, preset.baseUrl, replacement, emptyList(), untested = true)
                            }
                            settings.setChoice(preset.choice(provider))
                            player.play(audio.bytes, audio.mime)
                            closeSetup()
                        } catch (error: TimeoutCancellationException) {
                            setupStatus = testFailure(error)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            setupStatus = testFailure(error)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(tr(R.string.voice_test_use)) }
            },
            dismissButton = { TextButton(onClick = ::cancelSetup) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun VoiceCustomSettings(
    providers: List<Provider>, choice: VoiceChoice?, select: (VoiceChoice) -> Unit, busy: Boolean,
) {
    val provider = providers.firstOrNull { it.id == choice?.providerId }
    if (providers.isEmpty()) FooterNote(tr(R.string.voice_no_providers))
    else GroupCard {
        providers.forEachIndexed { index, candidate ->
            RadioRow(ModelsCopy.providerName(candidate), candidate.id == choice?.providerId, first = index == 0, enabled = !busy,
                onClick = {
                    if (candidate.id != choice?.providerId) {
                        val preset = VoiceProviderPreset.entries.firstOrNull { it.matches(candidate) }
                        select(preset?.choice(candidate) ?: VoiceChoice(
                            candidate.id, "", if (candidate.kind == ProviderConfig.Kind.Gemini) TtsProtocol.GeminiTts else TtsProtocol.OpenAiSpeech,
                        ))
                    }
                })
        }
    }
    if (choice == null || provider == null) return
    val modelBest = ProviderPresets.isModelBest(provider.baseUrl)
    val fixedProtocol = choice.protocol == TtsProtocol.DeepSeekTts || choice.protocol == TtsProtocol.DoubaoTts
    GroupCard {
        if (!fixedProtocol) {
            InputRow(tr(R.string.voice_model_name), choice.model, onChange = { if (!busy) select(choice.copy(model = it)) },
                placeholder = "", first = true)
        }
        InputRow(tr(R.string.voice_name), choice.voice, onChange = { if (!busy) select(choice.copy(voice = it)) },
            placeholder = "", first = fixedProtocol)
        if (!modelBest && choice.protocol == TtsProtocol.OpenAiSpeech) {
            var speed by remember(provider.id) { mutableStateOf(choice.speed.toString()) }
            InputRow(tr(R.string.voice_speed), speed, onChange = {
                if (!busy) {
                    speed = it
                    it.toFloatOrNull()?.let { value -> select(choice.copy(speed = value)) }
                }
            }, placeholder = "1.0", keyboard = KeyboardType.Decimal)
        }
        if (!modelBest && !fixedProtocol) {
            RadioRow(tr(R.string.voice_protocol_openai), choice.protocol == TtsProtocol.OpenAiSpeech, enabled = !busy,
                onClick = { select(choice.copy(protocol = TtsProtocol.OpenAiSpeech)) })
            RadioRow(tr(R.string.voice_protocol_gemini), choice.protocol == TtsProtocol.GeminiTts, enabled = !busy,
                onClick = { select(choice.copy(protocol = TtsProtocol.GeminiTts)) })
        }
    }
}
