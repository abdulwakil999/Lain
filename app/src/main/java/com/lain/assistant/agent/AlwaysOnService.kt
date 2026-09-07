package com.lain.assistant.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lain.assistant.LainApplication
import com.lain.assistant.MainActivity
import com.lain.assistant.R
import com.lain.assistant.automation.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps Lain resident between turns, when the user asks for it.
 *
 * What this actually does, stated plainly because the temptation is to describe it
 * as more: it holds a foreground service, which is Android's own mechanism for
 * "this process should not be reaped". Without it the app is an ordinary
 * background process — the system is free to kill it the moment memory is short,
 * and on the OEM builds this app is aimed at, it usually does. The visible
 * consequences are the ones users report as bugs: an alarm that fires several
 * minutes late because the process had to be rebuilt first, the wake word going
 * deaf after an hour, the Accessibility Service reconnecting slowly.
 *
 * What it does not do is make Lain think while nobody is talking to her. There is
 * no loop here, no polling, no model call. She is *available* rather than active,
 * and the notification says so, because a persistent notification claiming more
 * than the code does is the kind of overclaim this project keeps out of replies
 * and should keep out of the shade too.
 *
 * It costs battery — a resident process and an ongoing notification always do —
 * which is exactly why it is a switch the user throws rather than the default.
 *
 * [AgentForegroundService] is a different thing and both can run: that one exists
 * for the minutes a task is actually executing and holds a CPU wake lock. This one
 * holds no wake lock at all, so the phone still sleeps normally.
 */
class AlwaysOnService : Service() {

    companion object {
        private const val CHANNEL_ID = "lain_always_on"
        private const val NOTIFICATION_ID = 92
        const val ACTION_STOP = "com.lain.assistant.action.STOP_ALWAYS_ON"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Starts it, or does nothing if Android refuses.
         *
         * A refusal is possible — starting a foreground service from the background
         * is restricted from Android 12 — and it is not worth crashing over: the app
         * works without this, just less reliably, which is what the caller is told.
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlwaysOnService::class.java)
            )
            true
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlwaysOnService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Turned off from the notification, so the preference has to follow — or
            // the switch in Settings says on while nothing is running.
            LainApplication.appScope.launch(Dispatchers.IO) {
                runCatching {
                    com.lain.assistant.data.UserPreferencesRepository(applicationContext)
                        .setAlwaysOn(false)
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground itself can be refused — Android 12+ blocks a background
        // start, and 14 narrows it further. It throws from inside the service, where
        // the caller's runCatching cannot reach it, so an unguarded call here would
        // take the whole process down for a switch the user flipped days ago.
        val promoted = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }.isSuccess
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true

        // A restart is the one moment worth re-checking the schedule: the process may
        // have been killed while a task was due, and AlarmManager entries do not
        // survive every kind of restart the OEM managers perform.
        LainApplication.appScope.launch(Dispatchers.IO) {
            runCatching { Scheduler(applicationContext).restoreAll() }
        }

        // STICKY so the system brings it back if it does get killed under pressure,
        // which is the entire reason the user switched this on.
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lain always on", NotificationManager.IMPORTANCE_MIN)
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AlwaysOnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Lain is here")
            .setContentText("Staying loaded so alarms and the wake word don't drift.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }
}
