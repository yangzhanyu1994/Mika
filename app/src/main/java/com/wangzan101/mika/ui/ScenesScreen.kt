package com.wangzan101.mika.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.BluetoothScene
import com.wangzan101.mika.automation.BluetoothSceneMonitor
import com.wangzan101.mika.automation.SceneDispatcher
import com.wangzan101.mika.automation.SceneStore
import com.wangzan101.mika.automation.EnvironmentScene
import com.wangzan101.mika.automation.EnvironmentSceneStore
import com.wangzan101.mika.automation.EnvironmentSceneMonitor
import com.wangzan101.mika.automation.EnvironmentSceneDispatcher
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import java.util.UUID

@Composable
fun ScenesScreen(context: Context, onBack: () -> Unit, onPlans: () -> Unit, onOpenConversation: (String) -> Unit) {
    val store = remember { SceneStore.get(context).also { it.load() } }
    val rules by store.rules.collectAsState()
    val environment = remember { EnvironmentSceneStore.get(context).also { it.load() } }
    val environmentRules by environment.rules.collectAsState()
    val environmentMonitoring by EnvironmentSceneMonitor.monitoring.collectAsState()
    var environmentKind by remember { mutableStateOf<String?>(null) }
    var environmentEdit by remember { mutableStateOf<EnvironmentScene?>(null) }
    var environmentDelete by remember { mutableStateOf<EnvironmentScene?>(null) }

    val unavailable by store.unavailable.collectAsState()
    val monitoring by BluetoothSceneMonitor.monitoring.collectAsState()
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BluetoothScene?>(null) }
    var askDelete by remember { mutableStateOf<BluetoothScene?>(null) }
    var error by remember { mutableStateOf(false) }
    var requestedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun refreshEnvironment() {
        if (environment.rules.value.any { it.enabled }) {
            if (!EnvironmentSceneMonitor.start(context)) error = true
        } else EnvironmentSceneMonitor.stop(context)
    }
    environmentKind?.let { kind ->
        EnvironmentSceneEditor(context, kind, environmentEdit, error, onBack = { environmentKind = null; error = false }, onSave = { rule ->
            try { environment.save(rule); refreshEnvironment(); environmentKind = null } catch (_: Exception) { error = true }
        })
        return
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (EnvironmentSceneMonitor.fineGranted(context)) refreshEnvironment() else error = true
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = requestedAction
        requestedAction = null
        if (granted) action?.invoke() else error = true
    }
    fun withPermission(action: () -> Unit) {
        if (BluetoothSceneMonitor.permissionGranted(context)) action()
        else if (Build.VERSION.SDK_INT >= 31) {
            requestedAction = action
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    fun refreshMonitor() {
        if (store.rules.value.any { it.enabled }) {
            if (!BluetoothSceneMonitor.start(context)) error = true
        } else BluetoothSceneMonitor.stop(context)
    }
    if (editor) {
        BluetoothSceneEditor(context, editing, error, onBack = { editor = false; error = false }, onSave = { rule ->
            withPermission {
                try { store.save(rule); refreshMonitor(); editor = false } catch (_: Exception) { error = true }
            }
        })
        return
    }
    PageScaffold(stringResource(R.string.scene_title), onBack) {
        if (unavailable || error) Text(stringResource(R.string.scene_error), color = MaterialTheme.colorScheme.error)
        if (rules.any { it.enabled }) {
            FooterNote(stringResource(if (monitoring) R.string.scene_active else R.string.scene_inactive))
            if (!monitoring) TextButton(onClick = { withPermission { refreshMonitor() } }) { Text(stringResource(R.string.scene_start_monitor)) }
            if (!TriggerRuntime.canScheduleExactly(context)) {
                TextButton(onClick = { runCatching { context.startActivity(TriggerRuntime.exactAlarmSettings(context)) } }) {
                    Text(stringResource(R.string.scene_alarm_permission))
                }
            }
        }
        GroupLabel(stringResource(R.string.scene_trigger_types))
        GroupCard {
            ListRow(MikaIcons.Clock, stringResource(R.string.scene_time), first = true, onClick = onPlans) { RowChevron() }
            ListRow(MikaIcons.Screens, stringResource(R.string.scene_bluetooth), onClick = { editing = null; editor = true }) { RowChevron() }
            ListRow(MikaIcons.Globe, stringResource(R.string.scene_wifi), onClick = { environmentEdit = null; environmentKind = "wifi" }) { RowChevron() }
            ListRow(MikaIcons.Screens, stringResource(R.string.scene_location), onClick = { environmentEdit = null; environmentKind = "location" }) { RowChevron() }
        }
        if (environmentRules.any { it.enabled }) {
            FooterNote(stringResource(if (environmentMonitoring) R.string.env_active else R.string.env_inactive))
            if (!EnvironmentSceneMonitor.fineGranted(context)) TextButton(onClick = {
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(stringResource(R.string.env_request_location)) }
            if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) } }) {
                    Text(stringResource(R.string.env_background_settings))
                }
            }
            if (!environmentMonitoring && EnvironmentSceneMonitor.fineGranted(context)) TextButton(onClick = { refreshEnvironment() }) { Text(stringResource(R.string.scene_start_monitor)) }
        }
        GroupLabel(stringResource(R.string.scene_mine))
        GroupCard {
            if (rules.isEmpty() && environmentRules.isEmpty()) Text(stringResource(R.string.scene_empty), Modifier.padding(16.dp))
            rules.forEachIndexed { index, rule ->
                ListRow(MikaIcons.Screens, rule.title,
                    rule.deviceName + " · " + stringResource(if (rule.onConnect) R.string.scene_connected else R.string.scene_disconnected),
                    first = index == 0, detail = rule.goal.take(80), onClick = { editing = rule; editor = true }) {
                    Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                        val change = {
                            try { store.save(rule.copy(enabled = enabled)); refreshMonitor() } catch (_: Exception) { error = true }
                        }
                        if (enabled) withPermission(change) else change()
                    })
                }
                if (rule.lastThreadId != null) TextButton(onClick = { onOpenConversation(rule.lastThreadId) }) {
                    Text(stringResource(R.string.scene_last_run))
                }
                if (rule.enabled) TextButton(onClick = {
                    try { SceneDispatcher.dispatch(context, rule) } catch (_: Exception) { error = true }
                }) { Text(stringResource(R.string.scene_test)) }
                TextButton(onClick = { askDelete = rule }) { Text(stringResource(R.string.scene_delete)) }
            }
        }
        environmentRules.forEach { rule ->
            GroupCard {
                ListRow(MikaIcons.Globe, rule.title, rule.target + " · " + stringResource(
                    if (rule.type == "wifi") { if (rule.enter) R.string.scene_connected else R.string.scene_disconnected }
                    else { if (rule.enter) R.string.env_enter else R.string.env_exit }),
                    first = true, detail = rule.goal.take(80), onClick = { environmentEdit = rule; environmentKind = rule.type }) {
                    Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                        try { environment.save(rule.copy(enabled = enabled)); refreshEnvironment() } catch (_: Exception) { error = true }
                    })
                }
                rule.lastThreadId?.let { thread -> TextButton(onClick = { onOpenConversation(thread) }) { Text(stringResource(R.string.scene_last_run)) } }
                if (rule.enabled) TextButton(onClick = { try { EnvironmentSceneDispatcher.dispatch(context, rule) } catch (_: Exception) { error = true } }) { Text(stringResource(R.string.scene_test)) }
                TextButton(onClick = { environmentDelete = rule }) { Text(stringResource(R.string.scene_delete)) }
            }
        }
        Button(onClick = { editing = null; editor = true }, modifier = Modifier.fillMaxWidth().testTag("scene-new")) {
            Text(stringResource(R.string.scene_new))
        }
    }
    environmentDelete?.let { rule ->
        AlertDialog(onDismissRequest = { environmentDelete = null }, title = { Text(stringResource(R.string.scene_delete_confirm)) },
            text = { Text(rule.title) }, confirmButton = {
                TextButton(onClick = { try { environment.remove(rule.id); refreshEnvironment(); environmentDelete = null } catch (_: Exception) { error = true } }) { Text(stringResource(R.string.scene_delete)) }
            }, dismissButton = { TextButton(onClick = { environmentDelete = null }) { Text(stringResource(R.string.scene_cancel)) } })
    }
    askDelete?.let { rule ->
        AlertDialog(onDismissRequest = { askDelete = null }, title = { Text(stringResource(R.string.scene_delete_confirm)) },
            text = { Text(rule.title) }, confirmButton = {
                TextButton(onClick = {
                    try { store.remove(rule.id); refreshMonitor(); askDelete = null } catch (_: Exception) { error = true }
                }) { Text(stringResource(R.string.scene_delete)) }
            }, dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.scene_cancel)) } })
    }
}

@Composable
private fun BluetoothSceneEditor(context: Context, existing: BluetoothScene?, error: Boolean, onBack: () -> Unit, onSave: (BluetoothScene) -> Unit) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var goal by remember(existing?.id) { mutableStateOf(existing?.goal.orEmpty()) }
    var address by remember(existing?.id) { mutableStateOf(existing?.address.orEmpty()) }
    var deviceName by remember(existing?.id) { mutableStateOf(existing?.deviceName.orEmpty()) }
    var onConnect by remember(existing?.id) { mutableStateOf(existing?.onConnect ?: false) }
    var delay by remember(existing?.id) { mutableStateOf((existing?.delaySeconds ?: 60).toString()) }
    var choosing by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (EnvironmentSceneMonitor.fineGranted(context)) refreshEnvironment() else error = true
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosing = true else permissionError = true
    }
    PageScaffold(stringResource(R.string.scene_new), onBack) {
        OutlinedTextField(title, { title = it.take(80) }, label = { Text(stringResource(R.string.scene_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        GroupLabel(stringResource(R.string.scene_trigger))
        GroupCard {
            ListRow(MikaIcons.Screens, stringResource(R.string.scene_device), deviceName.ifBlank { stringResource(R.string.scene_choose_device) }, first = true,
                onClick = {
                    if (BluetoothSceneMonitor.permissionGranted(context)) choosing = true
                    else if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) { RowChevron() }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !onConnect, onClick = { onConnect = false }, label = { Text(stringResource(R.string.scene_disconnected)) })
                FilterChip(selected = onConnect, onClick = { onConnect = true }, label = { Text(stringResource(R.string.scene_connected)) })
            }
        }
        OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.scene_delay)) },
            supportingText = { Text(stringResource(R.string.scene_delay_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(goal, { goal = it.take(4000) }, label = { Text(stringResource(R.string.scene_goal)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        FooterNote(stringResource(R.string.scene_notification_note))
        if (permissionError || error) Text(stringResource(R.string.scene_error), color = MaterialTheme.colorScheme.error)
        Button(enabled = title.isNotBlank() && goal.isNotBlank() && address.isNotBlank() && (delay.toIntOrNull()?.let { it in 0..600 } == true),
            onClick = {
                onSave(BluetoothScene(existing?.id ?: UUID.randomUUID().toString(), title.trim(), address, deviceName,
                    onConnect, delay.toInt(), goal.trim(), enabled = existing?.enabled ?: true,
                    lastThreadId = existing?.lastThreadId, lastDispatchedAt = existing?.lastDispatchedAt))
            }, modifier = Modifier.fillMaxWidth().testTag("scene-save")) { Text(stringResource(R.string.scene_save)) }
    }
    if (choosing) {
        val devices = remember(choosing) {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                    .map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
            }.getOrDefault(emptyList())
        }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.scene_choose_device)) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) Text(stringResource(R.string.scene_no_devices))
                devices.forEach { (name, mac) -> TextButton(onClick = { deviceName = name.take(120); address = mac; choosing = false }) { Text(name) } }
            } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.scene_cancel)) } })
    }
}
