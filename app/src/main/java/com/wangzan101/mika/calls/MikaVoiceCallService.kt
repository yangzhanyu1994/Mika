package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the microphone available while a user-started call briefly leaves the app. */
class MikaVoiceCallService : Service() {
    internal class HungUp : Exception()

    private var current: Lease? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent?.action == ACTION_END) {
            current?.takeIf { it.id == id }?.end?.complete(Unit)
            if (current == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val lease = requested?.takeIf { it.id == id }
        if (lease == null && current != null) return START_NOT_STICKY
        try {
            // A canceled start still needs to acknowledge startForegroundService.
            ServiceCompat.startForeground(this, NOTIFICATION, notice(id), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (lease == null) {
                if (current == null) stopSelfResult(startId)
            } else {
                current = lease
                lease.ready.complete(true)
            }
        } catch (_: RuntimeException) {
            lease?.ready?.complete(false)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        current?.end?.complete(Unit)
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notice(id: String?): Notification {
        checkNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.mika_voice_call), NotificationManager.IMPORTANCE_LOW),
        )
        val returnToCall = PendingIntent.getActivity(
            this, NOTIFICATION, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            this, NOTIFICATION, Intent(this, MikaVoiceCallService::class.java)
                .setAction(ACTION_END).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.mika_voice_call))
            .setContentIntent(returnToCall)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, getString(R.string.calls_hangup), hangUp).build())
            .build()
    }

    private class Lease {
        val id: String = UUID.randomUUID().toString()
        val ready = CompletableDeferred<Boolean>()
        val end = CompletableDeferred<Unit>()
    }

    internal companion object {
        private const val CHANNEL = "mika_voice_call_active"
        private const val NOTIFICATION = 59241
        private const val ACTION_END = "com.wangzan101.mika.calls.END_MIKA_VOICE_CALL"
        private const val EXTRA_ID = "voiceCallId"

        // Service callbacks and the call dialog both run on the main thread.
        private var requested: Lease? = null

        suspend fun withMicrophone(context: Context, runCall: suspend () -> Unit) = coroutineScope {
            if (requested != null) throw MikaVoiceCall.Failure(R.string.calls_auto_busy)
            val lease = Lease()
            requested = lease
            val intent = Intent(context, MikaVoiceCallService::class.java).putExtra(EXTRA_ID, lease.id)
            var foreground = false
            try {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: RuntimeException) {
                    throw MikaVoiceCall.Failure(R.string.mika_voice_call_foreground)
                }
                foreground = withTimeoutOrNull(3_000) { lease.ready.await() } == true
                if (!foreground) throw MikaVoiceCall.Failure(R.string.mika_voice_call_foreground)
                val audio = async { runCall() }
                select<Unit> {
                    audio.onAwait { }
                    lease.end.onAwait {
                        audio.cancel()
                        throw HungUp()
                    }
                }
            } finally {
                if (requested === lease) {
                    requested = null
                    if (foreground || lease.ready.isCompleted) context.stopService(intent)
                }
            }
        }
    }
}
