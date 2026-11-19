package com.wangzan101.mika.automation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import android.os.SystemClock
import java.time.LocalTime
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
    private var wifiObserved = false
    private var observedSsid: String? = null
    private var locationFix: Location? = null
    private var bluetoothRegistered = false
    private var watchingCompound = false
    private val bluetoothStates = mutableMapOf<String, Boolean>()
    private val compoundGates = mutableMapOf<String, SceneMatchGate>()
    private val clock = object : Runnable {
        override fun run() {
            evaluateCompound()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    private val bluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                bluetoothStates.clear()
                evaluateCompound()
                return
            }
            if (!bluetoothAvailable()) return
            val connected = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return
            }
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val address = runCatching { device?.address?.uppercase(java.util.Locale.ROOT) }.getOrNull() ?: return
            if (bluetoothStates.put(address, connected) != connected) {
                evaluateCompound(event = SceneSignal.Bluetooth to address)
            }
        }
    }
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private val locations get() = getSystemService(LocationManager::class.java)
    private val store get() = EnvironmentSceneStore.get(this)

    private val locationListener = LocationListener { location ->
        if (!location.hasAccuracy() || location.accuracy <= 0 ||
            android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 120_000_000_000L) return@LocationListener
        locationFix = location
        evaluateCompound()
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
        val hasLocations = store.load() && store.rules.value.any { it.enabled && it.observes(SceneSignal.Location) }
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
        watchingCompound = rules.any { it.conditions != null }
        if (rules.any { it.observes(SceneSignal.Wifi) } && fineGranted(this)) startWifi()
        if (rules.any { it.observes(SceneSignal.Location) } && fineGranted(this) && backgroundGranted(this)) startLocation()
        if (rules.any { it.observes(SceneSignal.Bluetooth) } && BluetoothSceneMonitor.permissionGranted(this)) startBluetooth()
        if (watchingCompound) clock.run()
        active.value = callback != null || listening || bluetoothRegistered
        return START_STICKY
    }

    private fun startWifi() {
        val initial = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (initial == null || !initial.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifiGate.observe(null)
            observeCompoundWifi(null)
        }
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
            observeCompoundWifi(null)
            if (wifiNetwork != null) observeWifi(null)
            wifiNetwork = null
            return
        }
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo else null
        @Suppress("DEPRECATION") val fallback = getSystemService(WifiManager::class.java).connectionInfo
        val name = (info ?: fallback)?.ssid?.removeSurrounding("\"")
        wifiNetwork = network
        if (name.isNullOrBlank() || name == WifiManager.UNKNOWN_SSID) {
            wifiObserved = false
            evaluateCompound()
            return
        }
        observeCompoundWifi(name)
        observeWifi(name)
    }
    private fun onWifiLost(network: Network) {
        if (wifiNetwork != network) return
        wifiNetwork = null
        observeCompoundWifi(null)
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
                    locations.requestLocationUpdates(provider, 30_000L, if (watchingCompound) 0f else 20f, locationListener, Looper.getMainLooper())
                    listening = true
                }
            }
        }
    }
    private fun observeCompoundWifi(ssid: String?) {
        wifiObserved = true
        observedSsid = ssid
        evaluateCompound()
    }

    private fun startBluetooth() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetooth, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(bluetooth, filter)
            bluetoothRegistered = true
        }
        if (bluetoothRegistered && bluetoothAvailable()) {
            runCatching {
                getSystemService(BluetoothManager::class.java).getConnectedDevices(BluetoothProfile.GATT).forEach {
                    bluetoothStates[it.address.uppercase(java.util.Locale.ROOT)] = true
                }
            }
        }
    }

    private fun bluetoothAvailable(): Boolean = BluetoothSceneMonitor.permissionGranted(this) &&
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

    private fun observation(condition: SceneCondition): Boolean? = when (condition.signal) {
        SceneSignal.Wifi -> if (wifiObserved && fineGranted(this)) observedSsid == condition.target else null
        SceneSignal.Bluetooth -> if (bluetoothAvailable()) bluetoothStates[condition.target.uppercase(java.util.Locale.ROOT)] else null
        SceneSignal.Location -> observedLocation(condition)
    }

    private fun observedLocation(condition: SceneCondition): Boolean? {
        val fix = locationFix ?: return null
        if (!fineGranted(this) || !backgroundGranted(this) ||
            SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos > 120_000_000_000L) return null
        val lat = condition.latitude ?: return null
        val lon = condition.longitude ?: return null
        val distance = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, lat, lon, distance)
        return when {
            distance[0] + fix.accuracy < condition.radius -> true
            distance[0] - fix.accuracy > condition.radius -> false
            else -> null
        }
    }

    private fun currentMinute(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    private fun evaluateCompound(event: Pair<SceneSignal, String>? = null) {
        if (!watchingCompound) return
        val minute = currentMinute()
        for (rule in store.rules.value) {
            val conditions = rule.conditions ?: continue
            if (!rule.enabled) {
                compoundGates.remove(rule.id)
                continue
            }
            val gate = compoundGates.getOrPut(rule.id, ::SceneMatchGate)
            val matchingEvent = event != null && conditions.signals.any { it.signal == event.first && it.target.equals(event.second, true) }
            val token = gate.observe(conditions.matches(minute, ::observation), matchingEvent) ?: continue
            handler.postDelayed({
                val current = store.rules.value.firstOrNull { it.id == rule.id } ?: return@postDelayed
                if (!current.enabled || current.conditions != conditions || current.goal != rule.goal ||
                    current.delaySeconds != rule.delaySeconds || FeatureFlags.get(this).quitRequested() ||
                    !gate.current(token) || conditions.matches(currentMinute(), ::observation) != true) return@postDelayed
                runCatching { EnvironmentSceneDispatcher.dispatch(this, current) }.onFailure { notifyFailure() }
            }, rule.delaySeconds * 1000L)
        }
    }

    private fun EnvironmentScene.observes(signal: SceneSignal): Boolean =
        conditions?.signals?.any { it.signal == signal } ?: when (signal) {
            SceneSignal.Wifi -> type == "wifi"
            SceneSignal.Location -> type == "location"
            SceneSignal.Bluetooth -> false
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
        if (bluetoothRegistered) runCatching { unregisterReceiver(bluetooth) }
        bluetoothRegistered = false
        watchingCompound = false
        bluetoothStates.clear()
        compoundGates.clear()
        locationFix = null
        wifiObserved = false
        observedSsid = null
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
            System.currentTimeMillis() + 1000L, source = TriggerSource.User, sendWechat = rule.sendWechat))
        store.save(rule.copy(lastThreadId = thread))
    }
}
