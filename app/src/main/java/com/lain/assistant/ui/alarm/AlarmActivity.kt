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
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.lain.assistant.automation.AutomationResult
import com.lain.assistant.automation.PhoneController
import com.lain.assistant.automation.Scheduler
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainTheme
import kotlinx.coroutines.CoroutineScope
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

        /** Snooze length. Nine minutes is the convention every clock has used since the 1950s. */
        const val SNOOZE_MINUTES = 9

        fun raise(context: Context, task: ScheduledTask) {
            val intent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                putExtra(EXTRA_ID, task.id)
                putExtra(EXTRA_LABEL, task.label)
                putExtra(EXTRA_ACTION, task.action.name)
                putExtra(EXTRA_TARGET, task.target)
            }
            context.startActivity(intent)
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val action = runCatching { TaskAction.valueOf(intent.getStringExtra(EXTRA_ACTION) ?: "") }
            .getOrDefault(TaskAction.ALARM)

        startRinging()

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
                isLooping = true
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

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
    }

    private fun snooze(label: String) {
        stopRinging()
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
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
     * Places the call the user just asked for by tapping. The dialer takes the
     * foreground from here — Android gives the in-call UI to the system, and no
     * app can hold a PSTN call inside its own screen.
     */
    private fun placeCall(target: String) {
        stopRinging()
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
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

    /** Back must not silently dismiss an alarm — the user has to choose. */
    @Deprecated("Back is intentionally inert here")
    override fun onBackPressed() = Unit
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
