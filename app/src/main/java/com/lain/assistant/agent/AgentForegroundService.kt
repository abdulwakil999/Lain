package com.lain.assistant.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lain.assistant.MainActivity
import com.lain.assistant.R

/**
 * Keeps Lain working while the screen is locked or she's in the background.
 *
 * Without this, a long automation run lives and dies with the Activity: lock the
 * phone mid-task and Android is free to freeze the process, so the job silently
 * stalls halfway through. This service runs for exactly as long as a task is in
 * flight, holding a partial wake lock so the CPU keeps executing the tool loop
 * with the display off, and showing a notification with a Stop action so a
 * background task is never invisible or un-cancellable.
 */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "lain_agent_work"
        private const val NOTIFICATION_ID = 91
        const val ACTION_STOP = "com.lain.assistant.action.STOP_AGENT"
        const val EXTRA_STATUS = "status"

        /**
         * Android 12+ blocks starting a foreground service from the background.
         * Holding "display over other apps" (which the bubble requires) exempts
         * us, but a user driving Lain from the widget without that permission
         * would otherwise crash here — so a refusal degrades to "the task runs
         * without the wake lock" rather than taking the app down.
         */
        fun start(context: Context, status: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentForegroundService::class.java).putExtra(EXTRA_STATUS, status)
                )
            }
        }

        fun updateStatus(context: Context, status: String) = start(context, status)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ChatEngine.activeInstance?.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Working…"
        startForeground(NOTIFICATION_ID, buildNotification(status))
        acquireWakeLock()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        // PARTIAL_WAKE_LOCK keeps the CPU alive with the screen off — exactly what a
        // multi-step task needs. Timeout is a safety net so a wedged run can't hold
        // the CPU indefinitely.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lain::AgentWork").apply {
            setReferenceCounted(false)
            // Held only for the life of a task — the service stops as soon as the turn
            // ends, releasing this. The timeout is a backstop against a wedged run
            // pinning the CPU, not the expected duration.
            acquire(4 * 60 * 1000L)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(status: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lain working", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lain is on it")
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
