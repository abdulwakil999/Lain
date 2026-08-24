package com.lain.assistant.ui.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.lain.assistant.R
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lain.assistant.LainApplication
import com.lain.assistant.automation.AutomationResult
import com.lain.assistant.automation.PhoneController
import com.lain.assistant.automation.AppLauncher
import com.lain.assistant.automation.MessageFlow
import kotlinx.coroutines.withContext
import com.lain.assistant.automation.Scheduler
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The alarm, inside Lain.
 *
 * The alternative was handing `AlarmClock.ACTION_SET_ALARM` to whichever clock app
 * the manufacturer shipped — which means Lain can't show it, can't cancel it, and
 * can't tell the user whether it exists. Owning the ringing surface is what makes
 * "cancel my alarm" and "what have I got set" answerable at all.
 *
 * Shows over the lock screen and turns the display on, because an alarm that needs
 * the phone unlocked first is not an alarm.
 */
class AlarmActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_ID = "id"
        private const val EXTRA_PAYLOAD = "payload"

        /** Snooze length. Nine minutes is the convention every clock has used since the 1950s. */
        const val SNOOZE_MINUTES = 9

        /** Channel for the full-screen intent. Its own, so it can be IMPORTANCE_HIGH. */
        const val CHANNEL_ALARMS = "lain_alarms"

        /**
         * Brings the task's surface up, whatever state the phone is in.
         *
         * A plain `startActivity` from a broadcast receiver is refused on Android 10+
         * unless the app happens to hold "display over other apps" — which is why
         * alarms worked on a phone where the bubble had been enabled and scheduled
         * app launches silently did nothing everywhere else. Same call, same code,
         * different phone: the classic shape of a bug that looks intermittent.
         *
         * A full-screen intent is the sanctioned route. Android launches the activity
         * outright when the screen is off or locked, and shows a heads-up notification
         * the user can tap when they are mid-something — which is the correct
         * behaviour anyway, since hijacking the screen out of someone's hands is not.
         * The direct start is still attempted first, because when it is permitted it
         * is instant.
         */
        fun raise(context: Context, task: ScheduledTask) {
            val intent = intentFor(context, task)
            val started = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (started) return

            runCatching { postFullScreen(context, task, intent) }
        }

        private fun intentFor(context: Context, task: ScheduledTask) =
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                putExtra(EXTRA_ID, task.id)
                putExtra(EXTRA_LABEL, task.label)
                putExtra(EXTRA_ACTION, task.action.name)
                putExtra(EXTRA_TARGET, task.target)
                putExtra(EXTRA_PAYLOAD, task.payload)
                data = android.net.Uri.parse("lain://task/${task.id}/${System.currentTimeMillis()}")
            }

        private fun postFullScreen(context: Context, task: ScheduledTask, intent: Intent) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ALARMS, "Alarms and scheduled tasks", NotificationManager.IMPORTANCE_HIGH)
                        .apply { setBypassDnd(true) }
                )
            }
            val pending = PendingIntent.getActivity(
                context, task.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = when (task.action) {
                TaskAction.CALL -> "Time to call ${task.target}"
                TaskAction.WHATSAPP -> "Message ${task.target} on WhatsApp"
                TaskAction.OPEN_APP -> "Open ${task.target}"
                else -> task.label.ifBlank { "Alarm" }
            }
            manager.notify(
                task.id.hashCode(),
                NotificationCompat.Builder(context, CHANNEL_ALARMS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Lain")
                    .setContentText(title)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .setFullScreenIntent(pending, true)
                    .build()
            )
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var loopJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val action = runCatching { TaskAction.valueOf(intent.getStringExtra(EXTRA_ACTION) ?: "") }
            .getOrDefault(TaskAction.ALARM)

        val payload = intent.getStringExtra(EXTRA_PAYLOAD).orEmpty()

        // Only the two that are meant to wake somebody make a noise. An app opening
        // at a scheduled time should not sound like an emergency.
        if (action == TaskAction.ALARM || action == TaskAction.CALL) {
            startRinging()
            startLoopWatchdog()
            blockBackDismissal()
        }

        // These two have nothing to decide — the user already decided when they
        // scheduled it. Do the thing and get out of the way.
        if (action == TaskAction.OPEN_APP || action == TaskAction.WHATSAPP) {
            runNonInteractive(action, target, payload)
            return
        }

        setContent {
            LainTheme {
                AlarmScreen(
                    action = action,
                    label = label,
                    target = target,
                    onDismiss = { stopRinging(); finish() },
                    onSnooze = { snooze(label); finish() },
                    onCall = { placeCall(target) }
                )
            }
        }
    }

    /**
     * Lock-screen display. `setShowWhenLocked` is the modern route; the window
     * flags are the only thing that works before Android 8.1, and this app runs
     * back to Android 8.
     */
    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startRinging() {
        runCatching {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                // The alarm stream, so a silenced ringer doesn't silence the alarm —
                // that is the behaviour every user expects and Do Not Disturb honours.
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                // setLooping arrived in Android 9. Below that the ringtone plays once
                // and the watchdog below restarts it, so an alarm on an Android 8
                // phone still rings until it's dismissed rather than chirping once.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0, 700, 600)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    /**
     * Keeps the ringtone going on Android 8, which has no looping flag.
     *
     * Cheap: one check a second while the alarm screen is up, cancelled the moment
     * it is dismissed.
     */
    private fun startLoopWatchdog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return
        loopJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val r = ringtone ?: break
                if (!runCatching { r.isPlaying }.getOrDefault(true)) runCatching { r.play() }
            }
        }
    }

    private fun stopRinging() {
        loopJob?.cancel()
        loopJob = null
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
    }

    private fun snooze(label: String) {
        stopRinging()
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val app = applicationContext
        // Application-scoped on purpose: the work must outlive this Activity, which
        // finishes immediately. A fresh CoroutineScope per call would leak its Job.
        LainApplication.appScope.launch(Dispatchers.IO) {
            Scheduler(app).add(
                ScheduledTask(
                    label = label.ifBlank { "Alarm" },
                    action = TaskAction.ALARM,
                    triggerAtMillis = at
                )
            )
        }
    }

    /**
     * Carries out a scheduled task that needs a foreground app but no decision.
     *
     * This activity exists purely to be the thing Android is willing to launch;
     * having got the foreground, it hands off and finishes rather than showing a
     * screen nobody asked for.
     */
    private fun runNonInteractive(action: TaskAction, target: String, payload: String) {
        val app = applicationContext
        when (action) {
            TaskAction.OPEN_APP -> {
                val result = AppLauncher(app).openApp(target)
                if (result !is AutomationResult.Success) {
                    android.widget.Toast.makeText(app, "Lain couldn't open $target.", android.widget.Toast.LENGTH_LONG).show()
                }
            }

            TaskAction.WHATSAPP -> {
                LainApplication.appScope.launch(Dispatchers.IO) {
                    // Goes through MessageFlow so the contact name is resolved and the
                    // send is verified, rather than assuming a deep link means "sent".
                    val result = MessageFlow(app).send(target, payload, MessageFlow.Channel.WHATSAPP)
                    if (!result.success) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                app,
                                "WhatsApp to $target: ${result.error ?: result.result}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            else -> Unit
        }
        finish()
    }

    /**
     * Places the call the user just asked for by tapping. The dialer takes the
     * foreground from here — Android gives the in-call UI to the system, and no
     * app can hold a PSTN call inside its own screen.
     */
    private fun placeCall(target: String) {
        stopRinging()
        val app = applicationContext
        LainApplication.appScope.launch(Dispatchers.IO) {
            val phone = PhoneController(app)
            val number = (phone.lookupContact(target) as? AutomationResult.Success)
                ?.message?.lineSequence()?.firstOrNull()?.substringAfter(": ")?.trim()
                ?: target
            phone.placeCall(number)
        }
        finish()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    /**
     * Back must not silently dismiss an alarm — the user has to choose Stop or
     * Snooze. Registered as a callback rather than by overriding onBackPressed,
     * which is deprecated and, overridden without calling super, breaks predictive
     * back on Android 13+.
     */
    private fun blockBackDismissal() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }
}

@Composable
private fun AlarmScreen(
    action: TaskAction,
    label: String,
    target: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onCall: () -> Unit
) {
    val now = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LainInk)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(now, style = MaterialTheme.typography.displayLarge, color = LainSalmon)
        Spacer(Modifier.height(12.dp))
        Text(
            when (action) {
                TaskAction.CALL -> "Time to call $target"
                else -> label.ifBlank { "Alarm" }
            },
            style = MaterialTheme.typography.headlineSmall,
            color = LainCream,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(40.dp))

        if (action == TaskAction.CALL) {
            PixelButton(text = "Call $target", onClick = onCall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Lain doesn't dial on her own while you're not here — one tap and she will.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            PixelButton(text = "Not now", onClick = onDismiss)
        } else {
            PixelButton(text = "Stop", onClick = onDismiss)
            Spacer(Modifier.height(12.dp))
            PixelButton(text = "Snooze ${AlarmActivity.SNOOZE_MINUTES} minutes", onClick = onSnooze)
        }
    }
}
