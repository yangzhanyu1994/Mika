package com.wangzan101.mika.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.BluetoothSceneMonitor
import com.wangzan101.mika.automation.EnvironmentScene
import com.wangzan101.mika.automation.EnvironmentSceneMonitor
import com.wangzan101.mika.automation.SceneCondition
import com.wangzan101.mika.automation.SceneConditions
import com.wangzan101.mika.automation.SceneLocationLookup
import com.wangzan101.mika.automation.ScenePlace
import com.wangzan101.mika.automation.SceneSignal
import com.wangzan101.mika.automation.SceneTimeWindow
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.PageScaffold
import java.util.UUID
import kotlinx.coroutines.launch

private data class ConditionRow(val id: String = UUID.randomUUID().toString(), val condition: SceneCondition)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompoundSceneEditor(context: Context, existing: EnvironmentScene?, error: Boolean,
    onBack: () -> Unit, onSave: (EnvironmentScene) -> Unit) {
    val initial = existing?.conditions
    val rows = remember(existing?.id) {
        mutableStateListOf<ConditionRow>().apply {
            addAll((initial?.signals ?: listOf(SceneCondition(SceneSignal.Wifi))).map { ConditionRow(condition = it) })
        }
    }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var goal by remember { mutableStateOf(existing?.goal.orEmpty()) }
    var delay by remember { mutableStateOf((existing?.delaySeconds ?: 60).toString()) }
    var sendWechat by remember { mutableStateOf(existing?.sendWechat ?: false) }
    var all by remember { mutableStateOf(initial?.requireAll ?: false) }
    var window by remember { mutableStateOf(initial?.window) }
    var pickingStart by remember { mutableStateOf<Boolean?>(null) }
    var permissionError by remember { mutableStateOf(false) }
    var proceed by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = proceed
        proceed = null
        action?.invoke()
    }
    fun askFor(required: List<String>, action: () -> Unit) {
        val missing = required.distinct().filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            proceed = {
                if (missing.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) action()
                else permissionError = true
            }
            permissions.launch(missing.toTypedArray())
        }
    }

    PageScaffold(stringResource(R.string.scene_compound_title), onBack) {
        OutlinedTextField(title, { title = it.take(80) }, label = { Text(stringResource(R.string.scene_name)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        GroupLabel(stringResource(R.string.scene_conditions_title))
        if (rows.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!all, { all = false }, { Text(stringResource(R.string.scene_conditions_any)) })
                FilterChip(all, { all = true }, { Text(stringResource(R.string.scene_conditions_all)) })
            }
        }
        rows.forEachIndexed { index, row -> key(row.id) {
            GroupCard {
                CompoundConditionCard(context, row.condition, update = { next ->
                    val position = rows.indexOfFirst { it.id == row.id }
                    if (position >= 0) rows[position] = row.copy(condition = next)
                }, request = ::askFor)
                if (rows.size > 1) TextButton(onClick = { rows.removeAt(index) }) { Text(stringResource(R.string.scene_condition_remove)) }
            }
        } }
        TextButton(enabled = rows.size < 8, onClick = { rows += ConditionRow(condition = SceneCondition(SceneSignal.Wifi)) }) {
            Text(stringResource(R.string.scene_condition_add))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scene_window_enabled))
            Switch(checked = window != null, onCheckedChange = { window = if (it) SceneTimeWindow(8 * 60, 22 * 60) else null })
        }
        window?.let {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickingStart = true }) {
                    Text(stringResource(R.string.scene_window_start, formatClockMinute(it.start)))
                }
                TextButton(onClick = { pickingStart = false }) {
                    Text(stringResource(R.string.scene_window_end, formatClockMinute(it.end)))
                }
            }
            FooterNote(stringResource(if (it.valid) R.string.scene_window_hint else R.string.scene_window_invalid))
        }
        OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.scene_delay)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        FooterNote(stringResource(R.string.scene_delay_hint))
        OutlinedTextField(goal, { goal = it.take(4000) }, label = { Text(stringResource(R.string.scene_goal)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scene_send_wechat), Modifier.weight(1f))
            Switch(sendWechat, { sendWechat = it })
        }
        if (rows.any { it.condition.signal == SceneSignal.Location }) FooterNote(stringResource(R.string.env_permission_note))
        if (rows.any { it.condition.signal == SceneSignal.Bluetooth }) FooterNote(stringResource(R.string.scene_compound_bluetooth_hint))
        FooterNote(stringResource(R.string.scene_notification_note))
        if (permissionError || error) Text(stringResource(R.string.scene_error), color = MaterialTheme.colorScheme.error)
        val conditions = SceneConditions(rows.map { it.condition }, all, window)
        val seconds = delay.toIntOrNull()
        Button(enabled = title.isNotBlank() && goal.isNotBlank() && conditions.valid && seconds != null && seconds in 0..600,
            modifier = Modifier.fillMaxWidth(), onClick = {
                val requested = conditions.signals.flatMap { condition ->
                    when (condition.signal) {
                        SceneSignal.Wifi, SceneSignal.Location -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        SceneSignal.Bluetooth -> if (android.os.Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
                    }
                }
                askFor(requested) {
                    onSave(EnvironmentScene(existing?.id ?: UUID.randomUUID().toString(), title.trim(), "compound", title.trim(),
                        enter = true, delaySeconds = requireNotNull(seconds), goal = goal.trim(),
                        enabled = existing?.enabled ?: true, sendWechat = sendWechat, lastThreadId = existing?.lastThreadId,
                        conditions = conditions))
                }
            }) { Text(stringResource(R.string.scene_save)) }
    }
    val currentWindow = window
    val start = pickingStart
    if (currentWindow != null && start != null) {
        val clock = if (start) currentWindow.start else currentWindow.end
        val label = if (start) R.string.scene_window_start else R.string.scene_window_end
        ClockWheelDialog(stringResource(label, formatClockMinute(clock)), clock, { pickingStart = null }) {
            window = if (start) currentWindow.copy(start = it) else currentWindow.copy(end = it)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompoundConditionCard(context: Context, condition: SceneCondition,
    update: (SceneCondition) -> Unit, request: (List<String>, () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<ScenePlace>()) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SceneSignal.entries.forEach { signal ->
            val label = when (signal) {
                SceneSignal.Wifi -> R.string.scene_wifi
                SceneSignal.Bluetooth -> R.string.scene_bluetooth
                SceneSignal.Location -> R.string.scene_location
            }
            FilterChip(condition.signal == signal, { if (condition.signal != signal) update(SceneCondition(signal)) },
                { Text(stringResource(label)) }, enabled = !locating)
        }
    }
    when (condition.signal) {
        SceneSignal.Wifi -> {
            OutlinedTextField(condition.target, { update(condition.copy(target = it.take(128))) },
                label = { Text(stringResource(R.string.env_wifi_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { request(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                @Suppress("DEPRECATION") val ssid = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.removeSurrounding("\"") }.getOrNull()
                if (!ssid.isNullOrBlank() && ssid != WifiManager.UNKNOWN_SSID) update(condition.copy(target = ssid))
            } }) { Text(stringResource(R.string.env_use_current_wifi)) }
        }
        SceneSignal.Bluetooth -> {
            TextButton(onClick = {
                if (BluetoothSceneMonitor.permissionGranted(context)) choosing = true
                else request(listOf(Manifest.permission.BLUETOOTH_CONNECT)) { choosing = true }
            }) { Text(condition.name.ifBlank { stringResource(R.string.scene_choose_device) }) }
        }
        SceneSignal.Location -> {
            if (condition.target.isNotBlank()) Text(condition.target)
            OutlinedTextField(place, { place = it.take(120) }, label = { Text(stringResource(R.string.env_search_place)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !locating && place.isNotBlank(), onClick = {
                    locating = true
                    scope.launch {
                        results = runCatching { SceneLocationLookup.search(context, place.trim()) }.getOrNull().orEmpty()
                        locationError = results.isEmpty()
                        locating = false
                    }
                }) { Text(stringResource(R.string.env_search_button)) }
                TextButton(enabled = !locating, onClick = { request(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locating = true
                    scope.launch {
                        val fix = runCatching { SceneLocationLookup.current(context) }.getOrNull()
                        if (fix != null) update(condition.copy(target = context.getString(R.string.env_current_place),
                            latitude = fix.latitude, longitude = fix.longitude))
                        locationError = fix == null
                        locating = false
                    }
                } }) { Text(stringResource(R.string.env_use_current_location)) }
            }
            if (locationError) FooterNote(stringResource(R.string.env_location_failed))
            results.forEach { result ->
                TextButton(onClick = {
                    update(condition.copy(target = result.label.take(128), latitude = result.latitude, longitude = result.longitude))
                    results = emptyList()
                }) { Text(result.label) }
            }
            var radiusInput by remember { mutableStateOf(condition.radius.toInt().toString()) }
            OutlinedTextField(radiusInput, {
                radiusInput = it.filter(Char::isDigit).take(5)
                update(condition.copy(radius = radiusInput.toFloatOrNull() ?: 0f))
            }, label = { Text(stringResource(R.string.env_radius)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
    }
    val location = condition.signal == SceneSignal.Location
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(condition.present, { update(condition.copy(present = true)) },
            { Text(stringResource(if (location) R.string.scene_inside else R.string.scene_connected)) }, enabled = !locating)
        FilterChip(!condition.present, { update(condition.copy(present = false)) },
            { Text(stringResource(if (location) R.string.scene_outside else R.string.scene_disconnected)) }, enabled = !locating)
    }
    if (choosing) {
        val devices = remember { runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
        }.getOrDefault(emptyList()) }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.scene_choose_device)) },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) Text(stringResource(R.string.scene_no_devices))
                devices.forEach { (name, address) ->
                    TextButton(onClick = { update(condition.copy(target = address, name = name.take(128))); choosing = false }) { Text(name) }
                }
            } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
