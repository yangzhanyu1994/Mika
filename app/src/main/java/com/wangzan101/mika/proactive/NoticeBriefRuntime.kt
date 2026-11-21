package com.wangzan101.mika.proactive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Schedules the three local summaries with AlarmManager. Exact alarms are used when the
 * system allows them; otherwise the inexact idle alarm is used. A force-stop or an OEM
 * battery limit can still skip a fire. This does not touch task or call alarms.
 *
 * Collected text stays on the device. Verification codes are not written to the log.
 */
object NoticeBriefRuntime {
    const val ACTION = "com.wangzan101.mika.proactive.NOTICE_BRIEF"
    const val CHANNEL = "mika_notice_brief"
    private const val PREFS = "mika-notice-brief"
    private const val TAG = "MikaBrief"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun spec(context: Context, slot: BriefSlot): BriefSpec {
        val base = NoticeBrief.DEFAULTS.getValue(slot)
        val p = prefs(context)
        val key = slot.name
        return BriefSpec(
            enabled = p.getBoolean("$key.on", false),
            remindMinute = p.getInt("$key.remind", base.remindMinute),
            fromMinute = p.getInt("$key.from", base.fromMinute),
            toMinute = p.getInt("$key.to", base.toMinute),
        ).normalized()
    }

    fun save(context: Context, slot: BriefSlot, spec: BriefSpec) {
        val s = spec.normalized()
        prefs(context).edit()
            .putBoolean("${slot.name}.on", s.enabled)
            .putInt("${slot.name}.remind", s.remindMinute)
            .putInt("${slot.name}.from", s.fromMinute)
            .putInt("${slot.name}.to", s.toMinute)
            .apply()
        rearm(context)
        (context.applicationContext as? com.wangzan101.mika.MikaApp)?.proactive?.syncWatch()
    }

    fun anyEnabled(context: Context): Boolean = BriefSlot.entries.any { spec(context, it).enabled }

    fun capture(context: Context, record: NoticeRecord) {
        if (!anyEnabled(context)) return
        val item = NoticeBrief.accept(context.packageName, record.packageName, record.title, record.said, record.postedAtMs) ?: return
        val log = load(context)
        if (log.add(item)) saveLog(context, log)
    }

    fun rearm(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        for (slot in BriefSlot.entries) {
            val pending = pending(context, slot)
            alarms.cancel(pending)
            val spec = spec(context, slot)
            if (!spec.enabled) continue
            val at = NoticeBrief.nextFire(now, spec).toInstant().toEpochMilli()
            try {
                if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    fun handle(context: Context, slotName: String, synthetic: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        val slot = BriefSlot.entries.firstOrNull { it.name == slotName } ?: return
        val spec = spec(context, slot)
        if (!spec.enabled && !synthetic) {
            rearm(context)
            return
        }
        val zone = ZoneId.systemDefault()
        val fire = Instant.ofEpochMilli(nowMs).atZone(zone)
        val (start, end) = NoticeBrief.window(fire, spec)
        val items = load(context).between(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
        val app = context.applicationContext as? MikaApp
        val listening = app?.proactive?.authorized() == true
        val canPost = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
        val result = NoticeBrief.summarize(slot, items, context.packageName, listening, canPost, synthetic)
        if (result.blockedReason != "post") post(context, result)
        else Log.i(TAG, "brief not posted: notification permission missing slot=$slotName")
        rearm(context)
    }

    private fun post(context: Context, result: BriefResult) {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, context.getString(R.string.brief_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        TaskService.ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, result.slot.ordinal + 80,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_BRIEF, result.body),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val id = 41_000 + result.slot.ordinal
        notifications.notify(id, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(result.title)
            .setContentText(result.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(result.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    private fun pending(context: Context, slot: BriefSlot): PendingIntent {
        val intent = Intent(context, NoticeBriefAlarmReceiver::class.java).setAction(ACTION).putExtra("slot", slot.name)
        return PendingIntent.getBroadcast(
            context, 7_100 + slot.ordinal, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun file(context: Context) = File(context.filesDir, "notice-brief.json")

    private fun load(context: Context): NoticeBriefLog {
        val log = NoticeBriefLog()
        val text = file(context).takeIf { it.isFile }?.readText().orEmpty()
        if (text.isBlank()) return log
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                log.add(BriefItem(o.getString("id"), o.getString("pkg"), o.getLong("at"), o.optString("title"), o.optString("text"), o.optBoolean("otp")))
            }
        }
        return log
    }

    private fun saveLog(context: Context, log: NoticeBriefLog) {
        val arr = JSONArray()
        for (item in log.between(0, Long.MAX_VALUE)) {
            arr.put(JSONObject().put("id", item.id).put("pkg", item.packageName).put("at", item.atMs)
                .put("title", item.title).put("text", item.text).put("otp", item.otp))
        }
        val target = file(context)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) target.writeText(arr.toString())
    }
}

class NoticeBriefAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NoticeBriefRuntime.ACTION) return
        val slot = intent.getStringExtra("slot") ?: return
        val pending = goAsync()
        Thread {
            try {
                NoticeBriefRuntime.handle(context.applicationContext, slot)
            } catch (e: Exception) {
                Log.w("MikaBrief", "brief failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
