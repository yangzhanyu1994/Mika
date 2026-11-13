package com.wangzan101.mika.automation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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

/** User-enabled foreground observer. It does not initiate Bluetooth connections or scan. */
class BluetoothSceneMonitor : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = BluetoothEdgeGate()
    private var registered = false
    private val adapter get() = getSystemService(BluetoothManager::class.java)?.adapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                    gate.reset()
                    handler.removeCallbacksAndMessages(null)
                }
                refreshStatus()
                return
            }
            val connected = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return
            }
            if (!permissionGranted(this@BluetoothSceneMonitor) || !runCatching { adapter?.state == BluetoothAdapter.STATE_ON }.getOrDefault(false)) return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val address = try { device?.address } catch (_: SecurityException) { null } ?: return
            val pending = gate.observe(address, connected, SystemClock.elapsedRealtime(), 0) ?: return
            val rules = SceneStore.get(this@BluetoothSceneMonitor).rules.value.filter {
                it.enabled && it.address.equals(address, true) && it.onConnect == connected
            }
            rules.forEach { rule ->
                val delay = rule.delaySeconds * 1000L
                val delayed = pending.copy(dueAt = pending.dueAt + delay)
                handler.postDelayed({
                    val store = SceneStore.get(this@BluetoothSceneMonitor)
                    val current = store.rules.value.firstOrNull { it.id == rule.id } ?: return@postDelayed
                    if (current != rule || !permissionGranted(this@BluetoothSceneMonitor) ||
                        FeatureFlags.get(this@BluetoothSceneMonitor).quitRequested() ||
                        !gate.isDue(delayed, SystemClock.elapsedRealtime(), runCatching { adapter?.state == BluetoothAdapter.STATE_ON }.getOrDefault(false))) return@postDelayed
                    runCatching { SceneDispatcher.dispatch(this@BluetoothSceneMonitor, current) }.onFailure { notifyFailure() }
                }, delay)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.scene_monitor_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.scene_monitor_notification))
            .setContentText(getString(R.string.scene_monitor_notification_body))
            .setContentIntent(TaskService.openApp(this)).setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SceneStore.get(this)
        if (FeatureFlags.get(this).quitRequested() || !permissionGranted(this) || !store.load() || store.rules.value.none { it.enabled }) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
            registered = true
        }
        refreshStatus()
        return START_STICKY
    }

    private fun refreshStatus() {
        active.value = registered && permissionGranted(this) && runCatching { adapter?.state == BluetoothAdapter.STATE_ON }.getOrDefault(false)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (active.value) R.string.scene_monitor_notification else R.string.scene_inactive))
            .setContentText(getString(if (active.value) R.string.scene_monitor_notification_body else R.string.scene_inactive))
            .setContentIntent(TaskService.openApp(this)).setOngoing(true).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun notifyFailure() {
            val nm = getSystemService(NotificationManager::class.java)
            TaskService.ensureChannels(this)
            runCatching { nm.notify(FAILURE_ID, Notification.Builder(this, TaskService.CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.scene_failed_title))
                .setContentText(getString(R.string.scene_failed_body)).setContentIntent(TaskService.openApp(this)).setAutoCancel(true).build()) }
    }

    override fun onDestroy() {
        active.value = false
        handler.removeCallbacksAndMessages(null)
        gate.reset()
        if (registered) unregisterReceiver(receiver)
        registered = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "mika_scenes_monitor"
        private const val NOTIFICATION_ID = 81841
        private const val FAILURE_ID = 81842
        private val active = MutableStateFlow(false)
        val monitoring = active.asStateFlow()
        fun permissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, BluetoothSceneMonitor::class.java)); true
        }.getOrDefault(false)
        fun stop(context: Context) { context.stopService(Intent(context, BluetoothSceneMonitor::class.java)) }
    }
}

internal object SceneDispatcher {
    /** The persisted scheduler delivers the actual result through its existing notification path. */
    @Synchronized fun dispatch(context: Context, rule: BluetoothScene) {
        check(!FeatureFlags.get(context).quitRequested())
        val store = SceneStore.get(context)
        check(store.load())
        val current = store.rules.value.firstOrNull { it.id == rule.id && it.enabled } ?: error("Scene disabled")
        check(current == rule)
        val thread = ConversationStore.shared(context).newConversation()
        val trigger = TriggerDefinition(UUID.randomUUID().toString(), thread, rule.goal,
            System.currentTimeMillis() + 1000L, source = TriggerSource.User)
        TriggerRuntime.schedule(context, trigger)
        // Dispatch does not mean completion; the linked conversation carries the result.
        store.save(rule.copy(lastThreadId = thread, lastDispatchedAt = System.currentTimeMillis()))
    }
}
