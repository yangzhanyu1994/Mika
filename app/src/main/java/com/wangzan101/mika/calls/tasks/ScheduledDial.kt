package com.wangzan101.mika.calls.tasks

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.wangzan101.mika.calls.CallPhase

/** Persisted exact one-shot alarms. Each occurrence is claimed before Telecom is called. */
object ScheduledDial {
    private const val ACTION = "com.wangzan101.mika.calls.SCHEDULED_DIAL"
    private const val KEY_ID = "id"
    private const val KEY_DUE = "due"
    private fun alarm(context: Context, id: String, due: Long): PendingIntent {
        val intent = Intent(context, ScheduledDialReceiver::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("mika-dial").authority("task").appendPath(id).build())
            .putExtra(KEY_ID, id).putExtra(KEY_DUE, due)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun schedule(context: Context, task: CallAssistantTask) {
        val due = requireNotNull(task.dialAtMs)
        require(task.kind == CallTaskTexts.KIND_DIAL && task.dialScheduleState == "scheduled")
        check(TriggerRuntime.canScheduleExactly(context)) { "请先允许精确闹钟，否则不能可靠地按时拨打" }
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarm(context, task.id, due))
    }
    fun cancel(context: Context, id: String) {
        val pi = alarm(context, id, 0)
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }
    fun rearm(context: Context) {
        if (FeatureFlags.get(context).quitRequested()) return
        val store = (context.applicationContext as MikaApp).calls.tasks
        if (!store.load()) return
        if (store.recoverStaleScheduled() > 0) ScheduledDialService.report(context, false, "预约拨号状态未确认，请查看通话记录；不会自动重拨")
        val pending = store.snapshot().filter { it.kind == CallTaskTexts.KIND_DIAL && it.dialScheduleState == "scheduled" && it.dialAtMs != null }
        if (pending.isNotEmpty() && !TriggerRuntime.canScheduleExactly(context)) {
            ScheduledDialService.report(context, false, "精确闹钟权限不可用，预约拨号无法按时运行；请打开 Mika 检查权限")
            return
        }
        pending.forEach { task -> runCatching { schedule(context, task) }.onFailure {
            ScheduledDialService.report(context, false, "无法恢复预约拨号，请打开 Mika 查看任务", task.id)
        } }
    }
    internal fun id(intent: Intent) = intent.takeIf { it.action == ACTION }?.getStringExtra(KEY_ID)
    internal fun due(intent: Intent) = intent.getLongExtra(KEY_DUE, -1L)
    internal fun intent(context: Context, id: String, due: Long) = Intent(context, ScheduledDialService::class.java)
        .putExtra(KEY_ID, id).putExtra(KEY_DUE, due)
    internal fun values(intent: Intent?) = intent?.getStringExtra(KEY_ID) to (intent?.getLongExtra(KEY_DUE, -1L) ?: -1L)
}

class ScheduledDialReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ScheduledDial.id(intent) ?: return
        val due = ScheduledDial.due(intent)
        if (due <= 0 || FeatureFlags.get(context).quitRequested()) return
        runCatching { context.startForegroundService(ScheduledDial.intent(context, id, due)) }
            .onFailure { ScheduledDialService.report(context, false, "系统未能启动定时拨打，请打开 Mika 查看任务", id) }
    }
}

class ScheduledDialService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Mutex()
    private var outstanding = 0
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.calls_scheduled_channel), NotificationManager.IMPORTANCE_LOW))
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.calls_scheduled_preparing)).setOngoing(true).setContentIntent(TaskService.openApp(this)).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notice)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (id, due) = ScheduledDial.values(intent)
        if (id == null || due <= 0 || FeatureFlags.get(this).quitRequested()) { stopSelf(startId); return START_NOT_STICKY }
        outstanding++
        scope.launch {
            try {
                queue.withLock {
                    val calls = (application as MikaApp).calls
                    // A cold process restores its phone permissions and helper asynchronously.
                    // Give that restoration time before deciding the call is unavailable.
                    if (calls.settings.state.value.enabled) withTimeoutOrNull(20_000L) {
                        calls.state.first { it.prepared && it.shellConnected }
                    }
                    // Keep other due tasks queued briefly while a call is active; never dial indefinitely late.
                    while (calls.state.value.phase != CallPhase.IDLE && System.currentTimeMillis() <= due + MAX_LATENESS_MS &&
                        !FeatureFlags.get(this@ScheduledDialService).quitRequested()) delay(1000L)
                    if (!FeatureFlags.get(this@ScheduledDialService).quitRequested()) {
                        val task = withContext(Dispatchers.IO) { runCatching { calls.tasks.claimScheduled(id, due) }.getOrNull() }
                        if (task != null) {
                            if (System.currentTimeMillis() - due > MAX_LATENESS_MS) {
                                withContext(Dispatchers.IO) { runCatching { calls.tasks.finishScheduled(id, due, false, "已超过预约时间，未自动补拨") } }
                                report(this@ScheduledDialService, false, task.title + "：已超过预约时间，未自动补拨", task.id)
                            } else {
                                val result = runCatching { calls.dial(task.number, task.dialSimId, CallTaskBrief(task.say, emptyList(), task.target, task.cannotPromise)) }.getOrNull()
                                val submitted = result?.ok == true
                                val message = result?.message ?: "拨打结果未确认，请检查通话记录，系统不会自动重拨"
                                withContext(Dispatchers.IO) { runCatching { calls.tasks.finishScheduled(id, due, submitted, message) } }
                                report(this@ScheduledDialService, submitted, task.title + "：" + message, task.id)
                            }
                        }
                    }
                }
            } finally {
                outstanding--
                if (outstanding == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "mika_scheduled_dial"
        private const val ID = 81845
        private const val MAX_LATENESS_MS = 120_000L
        internal fun report(context: Context, submitted: Boolean, message: String, taskId: String? = null) {
            TaskService.ensureChannels(context)
            runCatching { context.getSystemService(NotificationManager::class.java).notify(taskId?.hashCode() ?: 81846,
                Notification.Builder(context, TaskService.CHANNEL_RESULT).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(if (submitted) R.string.calls_scheduled_submitted else R.string.calls_scheduled_failed))
                    .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                    .setContentIntent(TaskService.openApp(context)).setAutoCancel(true).build()) }
        }
    }
}
