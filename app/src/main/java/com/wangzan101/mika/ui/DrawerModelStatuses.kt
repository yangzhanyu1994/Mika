package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.voice.SpeechEngineSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DrawerModelStatuses(
    val language: DrawerBadge,
    val call: DrawerBadge,
    val readout: DrawerBadge,
    val speech: DrawerBadge,
) {
    val summary: DrawerBadge
        get() = listOf(language, call, readout, speech).let { statuses ->
            statuses.firstOrNull { it == DrawerBadge.NoKey }
                ?: statuses.firstOrNull { !it.positive }
                ?: DrawerBadge.Configured
        }
}

@Composable
internal fun rememberDrawerModelStatuses(open: Boolean): DrawerModelStatuses? {
    val app = LocalContext.current.applicationContext as? MikaApp ?: return null
    if (!open) return null
    val providers = app.providers
    val roles by providers.roles.collectAsState()
    val configs by providers.configs.collectAsState()
    val keyRevisions by providers.providerKeyRevisions.collectAsState()
    val providerList by providers.providers.collectAsState()
    val call by app.calls.settings.state.collectAsState()
    val speech by app.speech.choice.collectAsState()
    val silent by app.speech.systemSilent.collectAsState()
    val voice by app.voiceSettings.state.collectAsState()
    val status by produceState<DrawerModelStatuses?>(null, listOf(roles, configs, keyRevisions, providerList, call, speech, silent, voice)) {
        value = withContext(Dispatchers.IO) {
            fun hasKey(provider: Provider): Boolean = !providers.providerKey(provider.id).isNullOrBlank()
            fun needsKey(provider: Provider): Boolean {
                val preset = ProviderPresets.forHost(provider.baseUrl)
                return provider.kind != ProviderConfig.Kind.OpenAiCompat || provider.signIn != null ||
                    preset != null && !preset.selfHosted
            }

            val selected = listOf(providers.configFor(ModelRole.Main), providers.configFor(ModelRole.Chat))
                .map { config -> config?.let { providers.provider(it.providerId) } }
            val language = when {
                selected.filterNotNull().distinctBy(Provider::id).any { needsKey(it) && !hasKey(it) } -> DrawerBadge.NoKey
                selected.any { it == null } -> DrawerBadge.NotConfigured
                else -> DrawerBadge.Configured
            }
            val readoutStatus = when {
                !voice.enabled -> DrawerBadge.Disabled
                voice.choice?.model.isNullOrBlank() -> DrawerBadge.NotConfigured
                else -> {
                    val provider = voice.choice?.providerId?.let(providers::provider)
                    if (provider == null) DrawerBadge.NotConfigured
                    else configured(!needsKey(provider) || hasKey(provider))
                }
            }
            val speechStatus = when (val engine = app.voice.spec()) {
                is SpeechEngineSpec.Doubao -> configured(app.speech.doubaoCredentials() != null)
                is SpeechEngineSpec.Bailian -> configured(!app.speech.bailianKey(engine.region).isNullOrBlank())
                is SpeechEngineSpec.Provider -> configured(!needsKey(engine.provider) || hasKey(engine.provider))
                is SpeechEngineSpec.FunAsr, is SpeechEngineSpec.System -> DrawerBadge.Configured
                is SpeechEngineSpec.Unavailable -> when (engine.why) {
                    SpeechEngineSpec.Why.Off -> DrawerBadge.Disabled
                    SpeechEngineSpec.Why.DoubaoNotConfigured, SpeechEngineSpec.Why.BailianNotConfigured -> DrawerBadge.NoKey
                    else -> DrawerBadge.NotConfigured
                }
            }
            DrawerModelStatuses(language, configured(call.credentialsConfigured), readoutStatus, speechStatus)
        }
    }
    return status
}

private fun configured(present: Boolean) = if (present) DrawerBadge.Configured else DrawerBadge.NoKey
