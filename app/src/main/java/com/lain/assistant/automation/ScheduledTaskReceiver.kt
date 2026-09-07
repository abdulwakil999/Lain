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
import kotlinx.coroutines.flow.first
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

        /** A spoken line is a few seconds; anything longer is a stuck network call. */
        private const val SPEAK_TIMEOUT_MS = 20_000L

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

    private suspend fun act(context: Context, task: ScheduledTask) {
        when (task.action) {
            // The loud ones get a full-screen surface so they work with the screen off
            // and the phone locked, which is the entire point of an alarm.
            TaskAction.ALARM, TaskAction.CALL -> AlarmActivity.raise(context, task)

            TaskAction.REMIND -> notify(context, task, task.label)

            // The words are the point, so they are the notification, and she reads
            // them out where the phone's profile allows sound. A silent phone gets the
            // notification alone rather than a voice in a cinema.
            TaskAction.SAY -> {
                val line = task.label.replaceFirstChar { it.uppercase() }
                notify(context, task, line)
                if (audible(context)) speak(context, line)
            }

            TaskAction.SMS -> {
                // The target is whatever the user called them — "mama", not a number.
                // This used to hand that straight to SmsManager, which needs digits, so
                // every scheduled text to a name failed at the moment it mattered and
                // succeeded only if the user had typed a raw number.
                when (val result = MessageFlow(context).send(task.target, task.payload, MessageFlow.Channel.SMS)) {
                    else -> notify(
                        context, task,
                        if (result.success) "Sent to ${task.target}: ${task.payload}"
                        else "Couldn't text ${task.target} — ${result.error ?: result.result}"
                    )
                }
            }

            TaskAction.WHATSAPP -> {
                // Needs WhatsApp in the foreground, so it goes through the same
                // full-screen route as an alarm rather than a background start.
                AlarmActivity.raise(context, task)
            }

            TaskAction.OPEN_APP -> {
                // Also a full-screen intent. A plain startActivity from a receiver is
                // refused on Android 10+ unless the app happens to hold "display over
                // other apps", so scheduled app launches worked on some phones and
                // silently did nothing on others.
                AlarmActivity.raise(context, task)
            }
        }
    }

    /**
     * Whether saying something out loud is appropriate right now.
     *
     * Ringer mode is the user's own statement about that, and it is the one signal
     * that is right in every case a scheduled line could fire in — a meeting, a
     * cinema, a bedroom at eleven at night with the phone on silent.
     */
    private fun audible(context: Context): Boolean = runCatching {
        context.getSystemService(android.media.AudioManager::class.java)
            ?.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(false)

    /**
     * Reads a line aloud with the voice the user chose.
     *
     * Built here rather than borrowed from ChatEngine because this runs from a
     * broadcast with no chat session alive — that is the whole point of a schedule.
     * Bounded by a timeout: a network voice on a dead connection must not hold the
     * receiver's coroutine open indefinitely, and the notification has already
     * delivered the words either way.
     */
    private suspend fun speak(context: Context, line: String) {
        val prefs = com.lain.assistant.data.UserPreferencesRepository(context)
        val engine = com.lain.assistant.tts.TtsEngineProvider.create(
            context,
            kokoroEndpoint = prefs.kokoroEndpoint.first(),
            fishKey = com.lain.assistant.data.SecureKeyStore(context).getVoiceKey(),
            fishVoiceId = prefs.fishVoiceId.first()
        )
        runCatching {
            kotlinx.coroutines.withTimeout(SPEAK_TIMEOUT_MS) { engine.speak(line) }
        }
        runCatching { engine.stop() }
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
