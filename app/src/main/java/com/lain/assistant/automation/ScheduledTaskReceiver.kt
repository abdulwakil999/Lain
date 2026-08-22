package com.lain.assistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lain.assistant.MainActivity
import com.lain.assistant.R
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import com.lain.assistant.ui.alarm.AlarmActivity
import com.lain.assistant.LainApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires one scheduled task, then hands it back to the [Scheduler] to be re-armed
 * or retired.
 *
 * A broadcast receiver gets about ten seconds of life, so nothing slow happens
 * here. Ringing, prompting and dialling all belong to [AlarmActivity]; this only
 * decides which of them to raise.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "task_id"
        const val CHANNEL_REMINDERS = "lain_reminders"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    "Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()

        // goAsync() buys about ten seconds; the shared application scope keeps the
        // work off a per-call Job that nothing would ever cancel.
        LainApplication.appScope.launch(Dispatchers.IO) {
            try {
                val scheduler = Scheduler(app)
                val task = scheduler.all().firstOrNull { it.id == id }
                if (task != null && task.enabled) {
                    runCatching { act(app, task) }
                        .onFailure { AccessibilityMonitor.recordException("scheduled task ${task.label}", it) }
                }
                // Re-arm (or retire) regardless of whether the action itself worked —
                // a daily alarm must not stop repeating because one morning's
                // notification failed.
                scheduler.onFired(id)
            } finally {
                pending.finish()
            }
        }
    }

    private fun act(context: Context, task: ScheduledTask) = when (task.action) {
        // The two loud ones get a full-screen surface so they work with the screen
        // off and the phone locked, which is the entire point of an alarm.
        TaskAction.ALARM, TaskAction.CALL -> AlarmActivity.raise(context, task)

        TaskAction.REMIND -> notify(context, task, task.label)

        TaskAction.SMS -> {
            val result = PhoneController(context).sendSms(task.target, task.payload)
            // Reported either way. A text the user believes went out and didn't is
            // the failure that actually costs them something.
            notify(
                context, task,
                if (result is AutomationResult.Success) "Sent to ${task.target}: ${task.payload}"
                else "Couldn't text ${task.target}: ${(result as? AutomationResult.Failure)?.reason ?: "permission missing"}"
            )
        }

        TaskAction.OPEN_APP -> {
            val opened = AppLauncher(context).openApp(task.target)
            if (opened !is AutomationResult.Success) {
                notify(context, task, "Couldn't open ${task.target}.")
            } else Unit
        }
    }

    private fun notify(context: Context, task: ScheduledTask, text: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            task.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Lain")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
}

/**
 * Re-arms every scheduled task after a reboot.
 *
 * AlarmManager is wiped when the device restarts. Without this an alarm set on
 * Monday quietly stops existing the next time the phone reboots, and the user
 * only discovers it by not being woken up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val app = context.applicationContext
        val pending = goAsync()
        LainApplication.appScope.launch(Dispatchers.IO) {
            try {
                Scheduler(app).restoreAll()
            } finally {
                pending.finish()
            }
        }
    }
}
