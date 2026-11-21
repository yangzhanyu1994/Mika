package com.wangzan101.mika.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.location.EnvironmentBackground
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons

/** Settings for system location. Always-allow is granted only in the system page. */
@Composable
fun LocationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = EnvironmentBackground.granted(context)
    val services = context.getSystemService(LocationManager::class.java)?.let { manager ->
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
    } == true
    val askPrecise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val askCoarse = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    var auto by remember(tick) { mutableIntStateOf(if (flags.autoLocate()) 1 else 0) }
    PageScaffold(stringResource(R.string.location_title), onBack) {
        GroupCard {
            ListRow(MikaIcons.Globe, stringResource(R.string.location_status), statusText(fine, coarse, background, services), first = true)
            ListRow(null, stringResource(R.string.location_precise), stringResource(R.string.location_precise_sub), onClick = {
                askPrecise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            })
            ListRow(null, stringResource(R.string.location_coarse), stringResource(R.string.location_coarse_sub), onClick = {
                askCoarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            })
            ListRow(null, stringResource(R.string.location_always), stringResource(R.string.location_always_sub), onClick = {
                val intent = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                context.startActivity(intent)
            })
            ListRow(null, stringResource(R.string.location_services), stringResource(R.string.location_services_sub), onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            })
            ListRow(null, stringResource(R.string.location_auto), stringResource(R.string.location_auto_sub)) {
                MikaSwitch(auto == 1, { on ->
                    flags.setAutoLocate(on)
                    auto = if (on) 1 else 0
                })
            }
        }
        FooterNote(stringResource(R.string.location_note))
    }
}

@Composable
private fun statusText(fine: Boolean, coarse: Boolean, background: Boolean, services: Boolean): String {
    val access = when {
        !services -> stringResource(R.string.location_status_services_off)
        fine -> stringResource(R.string.location_status_fine)
        coarse -> stringResource(R.string.location_status_coarse)
        else -> stringResource(R.string.location_status_none)
    }
    return if (background) access + " · " + stringResource(R.string.location_status_background) else access
}
