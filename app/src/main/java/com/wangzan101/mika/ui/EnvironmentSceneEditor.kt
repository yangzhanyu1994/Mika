package com.wangzan101.mika.ui

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.automation.SceneLocationLookup
import com.wangzan101.mika.automation.ScenePlace
import kotlinx.coroutines.launch
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.EnvironmentScene
import com.wangzan101.mika.automation.EnvironmentSceneMonitor
import com.wangzan101.mika.ui.components.*
import java.util.UUID

@Composable
internal fun EnvironmentSceneEditor(context: Context, kind: String, existing: EnvironmentScene?, error: Boolean,
    onBack: () -> Unit, onSave: (EnvironmentScene) -> Unit) {
    var title by remember(existing?.id, kind) { mutableStateOf(existing?.title.orEmpty()) }
    var target by remember(existing?.id, kind) { mutableStateOf(existing?.target.orEmpty()) }
    var latitude by remember(existing?.id, kind) { mutableStateOf(existing?.latitude?.toString().orEmpty()) }
    var longitude by remember(existing?.id, kind) { mutableStateOf(existing?.longitude?.toString().orEmpty()) }
    var radius by remember(existing?.id, kind) { mutableStateOf((existing?.radius ?: 200f).toString()) }
    var goal by remember(existing?.id, kind) { mutableStateOf(existing?.goal.orEmpty()) }
    var delay by remember(existing?.id, kind) { mutableStateOf((existing?.delaySeconds ?: 60).toString()) }
    var enter by remember(existing?.id, kind) { mutableStateOf(existing?.enter ?: true) }
    var permissionError by remember { mutableStateOf(false) }
    var locationBusy by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<Int?>(null) }
    var searchText by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<ScenePlace>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending; pending = null
        if (EnvironmentSceneMonitor.fineGranted(context)) action?.invoke() else permissionError = true
    }
    fun withPermission(action: () -> Unit) {
        if (EnvironmentSceneMonitor.fineGranted(context)) action() else {
            pending = action
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    PageScaffold(stringResource(if (kind == "wifi") R.string.scene_wifi else R.string.scene_location), onBack) {
        OutlinedTextField(title, { title = it.take(80) }, label = { Text(stringResource(R.string.scene_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (kind == "wifi") {
            OutlinedTextField(target, { target = it.take(128) }, label = { Text(stringResource(R.string.env_wifi_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { withPermission {
                @Suppress("DEPRECATION") val name = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.removeSurrounding("\"") }.getOrNull()
                if (!name.isNullOrBlank() && name != WifiManager.UNKNOWN_SSID) target = name else permissionError = true
            } }) { Text(stringResource(R.string.env_use_current_wifi)) }
        } else {
            OutlinedTextField(target, { target = it.take(128) }, label = { Text(stringResource(R.string.env_place_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(latitude, { latitude = it.take(24) }, label = { Text(stringResource(R.string.env_latitude)) }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(longitude, { longitude = it.take(24) }, label = { Text(stringResource(R.string.env_longitude)) }, modifier = Modifier.weight(1f), singleLine = true)
            }
            TextButton(enabled = !locationBusy, onClick = { withPermission {
                locationBusy = true; locationMessage = R.string.env_location_loading
                scope.launch {
                    val location = runCatching { SceneLocationLookup.current(context) }.getOrNull()
                    locationBusy = false
                    if (location == null) locationMessage = R.string.env_location_failed
                    else {
                        latitude = location.latitude.toString(); longitude = location.longitude.toString()
                        if (target.isBlank()) target = context.getString(R.string.env_current_place)
                        locationMessage = R.string.env_location_found
                    }
                }
            } }) { Text(stringResource(R.string.env_use_current_location)) }
            OutlinedTextField(searchText, { searchText = it.take(120) },
                label = { Text(stringResource(R.string.env_search_place)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(enabled = !locationBusy && searchText.isNotBlank(), onClick = {
                locationBusy = true; locationMessage = R.string.env_search_loading; places = emptyList()
                scope.launch {
                    val results = runCatching { SceneLocationLookup.search(context, searchText.trim()) }.getOrNull()
                    locationBusy = false
                    places = results.orEmpty()
                    locationMessage = if (places.isEmpty()) R.string.env_search_failed else R.string.env_search_choose
                }
            }) { Text(stringResource(R.string.env_search_button)) }
            locationMessage?.let { Text(stringResource(it), color = if (it == R.string.env_location_failed || it == R.string.env_search_failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            places.forEach { place ->
                TextButton(onClick = {
                    target = place.label; latitude = place.latitude.toString(); longitude = place.longitude.toString()
                    places = emptyList(); locationMessage = R.string.env_location_found
                }, modifier = Modifier.fillMaxWidth()) { Text(place.label) }
            }
            OutlinedTextField(radius, { radius = it.take(8) }, label = { Text(stringResource(R.string.env_radius)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            FooterNote(stringResource(R.string.env_location_hint))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = enter, onClick = { enter = true }, label = { Text(stringResource(if (kind == "wifi") R.string.scene_connected else R.string.env_enter)) })
            FilterChip(selected = !enter, onClick = { enter = false }, label = { Text(stringResource(if (kind == "wifi") R.string.scene_disconnected else R.string.env_exit)) })
        }
        OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.scene_delay)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        FooterNote(stringResource(R.string.scene_delay_hint))
        OutlinedTextField(goal, { goal = it.take(4000) }, label = { Text(stringResource(R.string.scene_goal)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        FooterNote(stringResource(R.string.env_permission_note))
        FooterNote(stringResource(R.string.scene_notification_note))
        if (permissionError || error) Text(stringResource(R.string.scene_error), color = MaterialTheme.colorScheme.error)
        val lat = latitude.toDoubleOrNull(); val lon = longitude.toDoubleOrNull(); val meters = radius.toFloatOrNull()
        val validPosition = kind != "location" || (lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0 && meters != null && meters in 100f..10000f)
        Button(enabled = title.isNotBlank() && target.isNotBlank() && goal.isNotBlank() && validPosition && (delay.toIntOrNull()?.let { it in 0..600 } == true),
            onClick = { withPermission {
                onSave(EnvironmentScene(existing?.id ?: UUID.randomUUID().toString(), title.trim(), kind, target.trim(),
                    latitude = lat, longitude = lon, radius = meters ?: 200f, enter = enter, delaySeconds = delay.toInt(),
                    goal = goal.trim(), enabled = existing?.enabled ?: true, lastThreadId = existing?.lastThreadId))
            } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scene_save)) }
    }
}
