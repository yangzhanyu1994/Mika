package com.wangzan101.mika.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.caution.AppCautionMode
import com.wangzan101.mika.core.caution.CautionDefaults
import com.wangzan101.mika.core.caution.WirelessIdle
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CautionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val store = app.cautionStore
    val revision by store.revision.collectAsState()
    val canWrite = remember {
        context.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }
    val idleOn = WirelessIdle.enabled(store.wirelessIdle, canWrite)
    var editing by remember { mutableStateOf<String?>(null) }
    var showOthers by remember { mutableStateOf(false) }
    val others = remember { launchableApps(context.packageManager, context.packageName).filter { it.first !in CautionDefaults.SOCIAL } }

    PageScaffold(stringResource(R.string.caution_title), onBack) {
        Text(stringResource(R.string.caution_intro), Modifier.padding(bottom = 12.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        GroupCard {
            ListRow(MikaIcons.Shield, stringResource(R.string.caution_wireless), first = true) {
                MikaSwitch(idleOn, onCheckedChange = { store.setWirelessIdle(it) })
            }
        }
        Text(
            stringResource(if (canWrite) R.string.caution_wireless_on else R.string.caution_wireless_off),
            Modifier.padding(top = 8.dp, bottom = 14.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3,
        )
        GroupCard {
            AppModeRow(CautionDefaults.WECHAT, stringResource(R.string.caution_wechat), store, revision, first = true) { editing = CautionDefaults.WECHAT }
            AppModeRow(CautionDefaults.XHS, stringResource(R.string.caution_xhs), store, revision, first = false) { editing = CautionDefaults.XHS }
        }
        GroupLabel(stringResource(R.string.caution_other_apps))
        GroupCard {
            ListRow(MikaIcons.Apps, stringResource(R.string.caution_other_apps), first = true, onClick = { showOthers = !showOthers }) { RowChevron() }
            if (showOthers) {
                others.take(40).forEach { (pkg, name) ->
                    AppModeRow(pkg, name, store, revision, first = false) { editing = pkg }
                }
            }
        }
        GroupLabel(stringResource(R.string.caution_log))
        val log = remember(revision) { store.log().asReversed().take(40) }
        if (log.isEmpty()) {
            Text(stringResource(R.string.caution_log_empty), style = MikaType.Caption, color = MikaTheme.colors.ink3)
        } else {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                log.forEach { row ->
                    Text(
                        "${HH_MM.format(Instant.ofEpochMilli(row.atMs).atZone(ZoneId.systemDefault()))}  ${CautionDefaults.appName(row.packageName)}  ${row.summary}",
                        Modifier.padding(vertical = 3.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2,
                    )
                }
            }
        }
    }
    editing?.let { pkg ->
        LimitsDialog(pkg, store, onDismiss = { editing = null })
    }
}

@Composable
private fun AppModeRow(
    packageName: String, title: String, store: com.wangzan101.mika.settings.CautionStore, revision: Int, first: Boolean, onClick: () -> Unit,
) {
    val mode = remember(revision, packageName) { store.forPackage(packageName).mode }
    ListRow(MikaIcons.Shield, title, modeLabel(mode), first = first, onClick = onClick) { RowChevron() }
}

@Composable
private fun modeLabel(mode: AppCautionMode): String = stringResource(when (mode) {
    AppCautionMode.ReadOnly -> R.string.caution_mode_read
    AppCautionMode.ConfirmWrites -> R.string.caution_mode_confirm
    AppCautionMode.Normal -> R.string.caution_mode_normal
})

@Composable
private fun LimitsDialog(packageName: String, store: com.wangzan101.mika.settings.CautionStore, onDismiss: () -> Unit) {
    val current = store.forPackage(packageName)
    var mode by remember { mutableStateOf(current.mode) }
    var hourly by remember { mutableStateOf(current.limits.hourly.cap()) }
    var daily by remember { mutableStateOf(current.limits.daily.cap()) }
    var delay by remember { mutableStateOf("${current.limits.minDelayMs / 1000.0}-${current.limits.maxDelayMs / 1000.0}") }
    var hours by remember { mutableStateOf((current.limits.cooldownMs / 3_600_000L).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CautionDefaults.appName(packageName)) },
        text = {
            Column {
                ModePick(AppCautionMode.ReadOnly, mode) { mode = it }
                ModePick(AppCautionMode.ConfirmWrites, mode) { mode = it }
                ModePick(AppCautionMode.Normal, mode) { mode = it }
                OutlinedTextField(hourly, { hourly = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.caution_hourly)) })
                OutlinedTextField(daily, { daily = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_daily)) })
                OutlinedTextField(delay, { delay = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_delay)) })
                OutlinedTextField(hours, { hours = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_cooldown)) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.setMode(packageName, mode)
                val (minS, maxS) = delay.split("-").let { it.getOrNull(0)?.toDoubleOrNull() to it.getOrNull(1)?.toDoubleOrNull() }
                val minMs = ((minS ?: 0.8) * 1000).toLong()
                val maxMs = ((maxS ?: minS ?: 2.5) * 1000).toLong()
                store.setLimits(
                    packageName,
                    hourly.toIntOrNull() ?: current.limits.hourly,
                    daily.toIntOrNull() ?: current.limits.daily,
                    minMs, maxMs,
                    (hours.toLongOrNull() ?: 24L) * 3_600_000L,
                )
                onDismiss()
            }) { Text(stringResource(R.string.caution_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } },
    )
}

@Composable
private fun ModePick(mode: AppCautionMode, selected: AppCautionMode, onPick: (AppCautionMode) -> Unit) {
    ListRow(MikaIcons.Check, modeLabel(mode), first = mode == AppCautionMode.ReadOnly, onClick = { onPick(mode) }) {
        if (mode == selected) Text("✓", color = MikaTheme.colors.accent)
    }
}

private fun Int.cap(): String = if (this > 100_000) "" else toString()

private val HH_MM = DateTimeFormatter.ofPattern("MM-dd HH:mm")
