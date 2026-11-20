package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.BluetoothSceneMonitor
import com.wangzan101.mika.automation.EnvironmentSceneMonitor
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.WebSearchSettings
import com.wangzan101.mika.settings.VoiceProviderPreset
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.SpeechEngineSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DrawerConfiguration(
    val accessibility: DrawerBadge?,
    val work: DrawerBadge?,
    val speech: DrawerBadge?,
    val search: DrawerBadge?,
    val automation: DrawerBadge,
    val readout: DrawerBadge? = null,
    val readoutName: String? = null,
)

internal fun enabledBadge(enabled: Boolean): DrawerBadge =
    if (enabled) DrawerBadge.Monitoring else DrawerBadge.NotEnabled

private fun configuredBadge(configured: Boolean): DrawerBadge =
    if (configured) DrawerBadge.Configured else DrawerBadge.NotConfigured

@Composable
internal fun rememberDrawerConfiguration(open: Boolean, accessibilityEnabled: Boolean?): DrawerConfiguration? {
    val app = LocalContext.current.applicationContext as? MikaApp ?: return null
    if (!open) return null
    val providers = app.providers
    val roles by providers.roles.collectAsState()
    val configs by providers.configs.collectAsState()
    val keyRevisions by providers.providerKeyRevisions.collectAsState()
    val providerList by providers.providers.collectAsState()
    val speechChoice by app.speech.choice.collectAsState()
    val silent by app.speech.systemSilent.collectAsState()
    val voice by app.voiceSettings.state.collectAsState()
    val calls by app.calls.settings.state.collectAsState()
    val bluetooth by BluetoothSceneMonitor.monitoring.collectAsState()
    val environment by EnvironmentSceneMonitor.monitoring.collectAsState()
    val searchSettings = remember(app) { WebSearchSettings.get(app) }
    val configured by produceState<Triple<DrawerBadge, DrawerBadge, DrawerBadge>?>(null,
        listOf(roles, configs, keyRevisions, providerList, speechChoice, silent)) {
        value = withContext(Dispatchers.IO) {
            fun ready(provider: Provider): Boolean {
                val preset = ProviderPresets.forHost(provider.baseUrl)
                val needsKey = provider.kind != ProviderConfig.Kind.OpenAiCompat || provider.signIn != null ||
                    preset != null && !preset.selfHosted
                return !needsKey || !providers.providerKey(provider.id).isNullOrBlank()
            }

            val work = configuredBadge(providers.configFor(ModelRole.Main)?.let { providers.provider(it.providerId) }?.let(::ready) == true)
            val speech = when (val engine = app.voice.spec()) {
                is SpeechEngineSpec.Doubao -> configuredBadge(app.speech.doubaoCredentials() != null)
                is SpeechEngineSpec.Bailian -> configuredBadge(!app.speech.bailianKey(engine.region).isNullOrBlank())
                is SpeechEngineSpec.Provider -> configuredBadge(ready(engine.provider))
                is SpeechEngineSpec.FunAsr -> DrawerBadge.Configured
                is SpeechEngineSpec.System -> DrawerBadge.NotConfigured
                is SpeechEngineSpec.Unavailable -> when (engine.why) {
                    SpeechEngineSpec.Why.Off -> DrawerBadge.NotEnabled
                    else -> DrawerBadge.NotConfigured
                }
            }
            val search = searchSettings.get().let { settings ->
                when {
                    settings.problem != null -> DrawerBadge.NotConfigured
                    !settings.enabled -> DrawerBadge.NotEnabled
                    else -> DrawerBadge.Configured
                }
            }
            Triple(work, speech, search)
        }
    }
    val selectedProvider = providerList.firstOrNull { it.id == voice.choice?.providerId }
    val readoutName = if (voice.useDoubao) stringResource(R.string.speech_doubao_provider)
    else selectedProvider?.let { provider ->
        VoiceProviderPreset.entries.firstOrNull { it.matches(provider) }?.label ?: ModelsCopy.providerName(provider)
    }
    val readout = when {
        !voice.enabled -> DrawerBadge.NotEnabled
        voice.useDoubao -> configuredBadge(calls.credentialsConfigured)
        else -> configuredBadge(selectedProvider != null && voice.hasModel(false))
    }
    return DrawerConfiguration(
        accessibilityEnabled?.let(::enabledBadge),
        configured?.first,
        configured?.second,
        configured?.third,
        enabledBadge(bluetooth || environment),
        readout,
        readoutName,
    )
}

@Composable
internal fun DrawerInset(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
        .clip(MikaShapes.Tile).background(MikaTheme.colors.bg)) {
        content()
    }
}

@Composable
internal fun DrawerStatusEntry(
    icon: ImageVector,
    title: String,
    badge: DrawerBadge?,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c.ink2)
        Text(title, Modifier.weight(1f), style = MikaType.Caption, color = c.ink)
        badge?.let { DrawerStatusBadge(it) }
        Icon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = c.ink3)
    }
}

@Composable
internal fun DrawerConnectors(store: McpServerStore, onEdit: (McpServerConfig) -> Unit, onAdd: () -> Unit) {
    val servers by store.servers.collectAsState()
    val health by store.health.collectAsState()
    val c = MikaTheme.colors
    DrawerInset {
        if (servers.isEmpty()) {
            Text(stringResource(R.string.mcp_servers_empty), Modifier.padding(12.dp),
                style = MikaType.Hint, color = c.ink3)
        }
        servers.forEach { server ->
            val badge = when {
                !server.enabled -> DrawerBadge.NotEnabled
                health[server.id]?.ok == true -> DrawerBadge.Connected
                else -> DrawerBadge.Disconnected
            }
            DrawerStatusEntry(MikaIcons.Link, server.name, badge) { onEdit(server) }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = c.hair)
        DrawerStatusEntry(MikaIcons.Plus, stringResource(R.string.mcp_servers_add), null, onAdd)
    }
}
