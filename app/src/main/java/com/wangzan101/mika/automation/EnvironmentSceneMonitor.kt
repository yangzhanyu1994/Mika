package com.wangzan101.mika.automation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSource
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Running only for explicitly enabled rules. An observation is not an execution result. */
class EnvironmentSceneMonitor : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val wifiGate = WifiTransitionGate()
    private val locationGate = LocationTransitionGate()
    private val epochs = mutableMapOf<String, Long>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listening = false
    private var wifiNetwork: Network? = null
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private val locations get() = getSystemService(LocationManager::class.java)
    private val store get() = EnvironmentSceneStore.get(this)

    private val locationListener = LocationListener { location ->
        if (!location.hasAccuracy() || location.accuracy <= 0 ||
            android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 120_000_000_000L) return@LocationListener
        store.rules.value.filter { it.enabled && it.type == "location" }.forEach { rule ->
            val lat = rule.latitude ?: return@forEach
            val lon = rule.longitude ?: return@forEach
            val result = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, lat, lon, result)
            // A boundary-overlapping fix cannot confirm that a pending edge still holds.
            if (result[0] - location.accuracy <= rule.radius && result[0] + location.accuracy >= rule.radius) {
                epochs[rule.id] = (epochs[rule.id] ?: 0L) + 1
            }
            val transition = locationGate.observe(rule.id, result[0], location.accuracy, rule.radius) ?: return@forEach
            onEdge(rule, transition)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, getString(R.string.scene_monitor_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        promote()
    }

    private fun promote() {
        val note = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.env_monitor_title)).setContentText(getString(R.string.env_monitor_body))
            .setOngoing(true).setContentIntent(TaskService.openApp(this)).build()
        val hasLocations = store.load() && store.rules.value.any { it.enabled && it.type == "location" }
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (hasLocations && fineGranted(this) && backgroundGranted(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(ID, note, type)
        } else startForeground(ID, note)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.load() || FeatureFlags.get(this).quitRequested() || store.rules.value.none { it.enabled }) {
            stopSelf(); return START_NOT_STICKY
        }
        val promoted = runCatching { promote() }.isSuccess
        if (!promoted) { stopSelf(); return START_NOT_STICKY }
        stopObservers()
        backgroundAtStart.value = backgroundGranted(this)
        val rules = store.rules.value.filter { it.enabled }
        if (rules.any { it.type == "wifi" } && fineGranted(this)) startWifi()
        if (rules.any { it.type == "location" } && fineGranted(this) && backgroundGranted(this)) startLocation()
        active.value = callback != null || listening
        return START_STICKY
    }

    private fun startWifi() {
        val initial = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (initial == null || !initial.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiGate.observe(null)
        val observer = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onWifi(network, caps)
            override fun onLost(network: Network) = onWifiLost(network)
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onWifi(network, caps)
            override fun onLost(network: Network) = onWifiLost(network)
        }
        runCatching { connectivity.registerDefaultNetworkCallback(observer, handler); callback = observer }
    }
    private fun onWifi(network: Network, caps: NetworkCapabilities) {
        if (!fineGranted(this)) return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            if (wifiNetwork != null) observeWifi(null)
            wifiNetwork = null
            return
        }
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo else null
        @Suppress("DEPRECATION") val fallback = getSystemService(WifiManager::class.java).connectionInfo
        val name = (info ?: fallback)?.ssid?.removeSurrounding("\"")
        if (name.isNullOrBlank() || name == WifiManager.UNKNOWN_SSID) return
        wifiNetwork = network
        observeWifi(name)
    }
    private fun onWifiLost(network: Network) {
        if (wifiNetwork != network) return
        wifiNetwork = null
        observeWifi(null)
    }
    private fun observeWifi(ssid: String?) {
        val change = wifiGate.observe(ssid) ?: return
        store.rules.value.filter { it.enabled && it.type == "wifi" }.forEach { rule ->
            when {
                change.disconnected == rule.target -> onEdge(rule, false)
                change.connected == rule.target -> onEdge(rule, true)
            }
        }
    }
    private fun startLocation() {
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            runCatching {
                if (locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 30_000L, 20f, locationListener, Looper.getMainLooper())
                    listening = true
                }
            }
        }
    }
    private fun onEdge(rule: EnvironmentScene, entered: Boolean) {
        val epoch = (epochs[rule.id] ?: 0L) + 1
        epochs[rule.id] = epoch
        if (rule.enter != entered) return
        handler.postDelayed({
            val current = store.rules.value.firstOrNull { it.id == rule.id } ?: return@postDelayed
            if (current != rule || epochs[rule.id] != epoch || !fineGranted(this) || FeatureFlags.get(this).quitRequested()) return@postDelayed
            runCatching { EnvironmentSceneDispatcher.dispatch(this, current) }.onFailure { notifyFailure() }
        }, rule.delaySeconds * 1000L)
    }
    private fun notifyFailure() {
        TaskService.ensureChannels(this)
        runCatching { getSystemService(NotificationManager::class.java).notify(81844,
            Notification.Builder(this, TaskService.CHANNEL_RESULT).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.scene_failed_title)).setContentText(getString(R.string.scene_failed_body))
                .setContentIntent(TaskService.openApp(this)).setAutoCancel(true).build()) }
    }
    private fun stopObservers() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; callback = null
        if (listening) runCatching { locations.removeUpdates(locationListener) }
        listening = false; wifiNetwork = null; wifiGate.reset(); locationGate.clear(); epochs.clear()
        handler.removeCallbacksAndMessages(null); active.value = false
    }
    override fun onDestroy() { stopObservers(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "mika_environment_scenes"
        private const val ID = 81843
        private val active = MutableStateFlow(false)
        private val backgroundAtStart = MutableStateFlow(false)
        val registeredBackground = backgroundAtStart.asStateFlow()
        val monitoring = active.asStateFlow()
        fun fineGranted(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun backgroundGranted(c: Context) = Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, EnvironmentSceneMonitor::class.java)); true }.getOrDefault(false)
        fun stop(c: Context) { c.stopService(Intent(c, EnvironmentSceneMonitor::class.java)) }
    }
}

internal object EnvironmentSceneDispatcher {
    @Synchronized fun dispatch(context: Context, rule: EnvironmentScene) {
        check(!FeatureFlags.get(context).quitRequested())
        val store = EnvironmentSceneStore.get(context)
        check(store.load() && store.rules.value.any { it == rule && it.enabled })
        val thread = ConversationStore.shared(context).newConversation()
        TriggerRuntime.schedule(context, TriggerDefinition(UUID.randomUUID().toString(), thread,
            rule.goal + "\n\n" + context.getString(R.string.scene_result_instruction),
            System.currentTimeMillis() + 1000L, source = TriggerSource.User))
        store.save(rule.copy(lastThreadId = thread))
    }
}
