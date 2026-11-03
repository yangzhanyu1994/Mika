package com.wangzan101.mika.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.caution.AppCautionMode
import com.wangzan101.mika.core.caution.CautionDefaults
import com.wangzan101.mika.core.caution.CautionLimits
import com.wangzan101.mika.ui.components.FooterNote
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
    val enabled = remember(revision) { store.reduceRisk }
    val pace = remember(revision) {
        val min = store.paceMinMs ?: CautionDefaults.DELAY_MIN_MS
        val max = store.paceMaxMs ?: CautionDefaults.DELAY_MAX_MS
        "${min / 1000.0}-${max / 1000.0}"
    }
    var delay by remember(pace) { mutableStateOf(pace) }
    var editing by remember { mutableStateOf<String?>(null) }
    var showOthers by remember { mutableStateOf(false) }
    val others = remember { launchableApps(context.packageManager, context.packageName).filter { it.first !in CautionDefaults.GUARDED } }

    PageScaffold(stringResource(R.string.caution_title), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Shield, stringResource(R.string.caution_switch), stringResource(R.string.caution_switch_sub),
                first = true, onClick = { store.setReduceRisk(!enabled) },
            ) {
                MikaSwitch(enabled, { store.setReduceRisk(it) }, Modifier.testTag("caution-reduce-risk"))
            }
        }
        Text(stringResource(R.string.caution_intro), Modifier.padding(top = 12.dp, bottom = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        GroupLabel(stringResource(R.string.caution_pace))
        GroupCard {
            OutlinedTextField(
                delay, { delay = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.caution_delay)) },
                supportingText = { Text(stringResource(R.string.caution_delay_hint)) },
            )
            TextButton(onClick = {
                val (minS, maxS) = delay.split("-").let { it.getOrNull(0)?.toDoubleOrNull() to it.getOrNull(1)?.toDoubleOrNull() }
                val minMs = ((minS ?: CautionDefaults.DELAY_MIN_MS / 1000.0) * 1000).toLong()
                val maxMs = ((maxS ?: minS ?: CautionDefaults.DELAY_MAX_MS / 1000.0) * 1000).toLong()
                store.setPace(minMs, maxMs)
            }, Modifier.padding(start = 8.dp, bottom = 4.dp)) { Text(stringResource(R.string.caution_save)) }
        }
        FooterNote(stringResource(R.string.caution_foot))
        GroupCard {
            AppModeRow(CautionDefaults.WECHAT, stringResource(R.string.caution_wechat), store, revision, first = true) { editing = CautionDefaults.WECHAT }
            AppModeRow(CautionDefaults.XHS, stringResource(R.string.caution_xhs), store, revision) { editing = CautionDefaults.XHS }
            AppModeRow(CautionDefaults.TAOBAO, stringResource(R.string.caution_taobao), store, revision) { editing = CautionDefaults.TAOBAO }
            AppModeRow(CautionDefaults.XIANYU, stringResource(R.string.caution_xianyu), store, revision) { editing = CautionDefaults.XIANYU }
            AppModeRow(CautionDefaults.JD, stringResource(R.string.caution_jd), store, revision) { editing = CautionDefaults.JD }
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
    packageName: String, title: String, store: com.wangzan101.mika.settings.CautionStore, revision: Int, first: Boolean = false, onClick: () -> Unit,
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
    val browse = packageName in CautionDefaults.BROWSE
    var mode by remember { mutableStateOf(current.mode) }
    var hourly by remember { mutableStateOf(current.limits.hourly.cap()) }
    var daily by remember { mutableStateOf(current.limits.daily.cap()) }
    var delay by remember { mutableStateOf("${current.limits.minDelayMs / 1000.0}-${current.limits.maxDelayMs / 1000.0}") }
    var hours by remember { mutableStateOf((current.limits.cooldownMs / 3_600_000L).toString()) }
    var searchH by remember { mutableStateOf(current.limits.searchHourly.cap()) }
    var searchD by remember { mutableStateOf(current.limits.searchDaily.cap()) }
    var pageH by remember { mutableStateOf(current.limits.pageHourly.cap()) }
    var pageD by remember { mutableStateOf(current.limits.pageDaily.cap()) }
    var detailH by remember { mutableStateOf(current.limits.detailHourly.cap()) }
    var detailD by remember { mutableStateOf(current.limits.detailDaily.cap()) }
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
                if (browse) {
                    OutlinedTextField(searchH, { searchH = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_search_hour)) })
                    OutlinedTextField(searchD, { searchD = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_search_day)) })
                    OutlinedTextField(pageH, { pageH = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_page_hour)) })
                    OutlinedTextField(pageD, { pageD = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_page_day)) })
                    OutlinedTextField(detailH, { detailH = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_detail_hour)) })
                    OutlinedTextField(detailD, { detailD = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.caution_detail_day)) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.setMode(packageName, mode)
                val (minS, maxS) = delay.split("-").let { it.getOrNull(0)?.toDoubleOrNull() to it.getOrNull(1)?.toDoubleOrNull() }
                val minMs = ((minS ?: CautionDefaults.DELAY_MIN_MS / 1000.0) * 1000).toLong()
                val maxMs = ((maxS ?: minS ?: CautionDefaults.DELAY_MAX_MS / 1000.0) * 1000).toLong()
                store.setLimits(
                    packageName,
                    hourly.toIntOrNull() ?: current.limits.hourly,
                    daily.toIntOrNull() ?: current.limits.daily,
                    minMs, maxMs,
                    (hours.toLongOrNull() ?: 24L) * 3_600_000L,
                    searchHourly = searchH.toIntOrNull(),
                    searchDaily = searchD.toIntOrNull(),
                    pageHourly = pageH.toIntOrNull(),
                    pageDaily = pageD.toIntOrNull(),
                    detailHourly = detailH.toIntOrNull(),
                    detailDaily = detailD.toIntOrNull(),
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

private fun Int.cap(): String = if (this >= CautionLimits.UNLIMITED) "" else toString()

private val HH_MM = DateTimeFormatter.ofPattern("MM-dd HH:mm")
