package com.wangzan101.mika.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
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
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import java.util.UUID

@Composable
fun ScenesScreen(context: Context, onBack: () -> Unit, onPlans: () -> Unit, onOpenConversation: (String) -> Unit) {
    val store = remember { SceneStore.get(context).also { it.load() } }
    val rules by store.rules.collectAsState()
    val unavailable by store.unavailable.collectAsState()
    val monitoring by BluetoothSceneMonitor.monitoring.collectAsState()
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BluetoothScene?>(null) }
    var askDelete by remember { mutableStateOf<BluetoothScene?>(null) }
    var error by remember { mutableStateOf(false) }
    var requestedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
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
            ListRow(MikaIcons.Globe, stringResource(R.string.scene_wifi), stringResource(R.string.scene_coming)) {}
            ListRow(MikaIcons.Screens, stringResource(R.string.scene_location), stringResource(R.string.scene_coming)) {}
        }
        GroupLabel(stringResource(R.string.scene_mine))
        GroupCard {
            if (rules.isEmpty()) Text(stringResource(R.string.scene_empty), Modifier.padding(16.dp))
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
        Button(onClick = { editing = null; editor = true }, modifier = Modifier.fillMaxWidth().testTag("scene-new")) {
            Text(stringResource(R.string.scene_new))
        }
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
