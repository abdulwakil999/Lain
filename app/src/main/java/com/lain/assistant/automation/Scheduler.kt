package com.lain.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lain.assistant.data.Repeat
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.scheduleStore by preferencesDataStore(name = "lain_schedule")
private val TASKS_KEY = stringPreferencesKey("scheduled_tasks_json")

/**
 * Lain's own scheduler: alarms, reminders and recurring tasks.
 *
 * Deliberately not a wrapper around the Clock app's `ACTION_SET_ALARM` intent.
 * That hands the job to whichever clock the OEM shipped, gives back no handle to
 * the alarm afterwards, and can't say whether it was actually set — so "cancel
 * tomorrow's alarm" becomes impossible and "did that work?" becomes a guess. An
 * assistant that can set something it can neither list nor cancel is not much of
 * an assistant.
 *
 * Everything here rides on AlarmManager, is persisted, and is re-armed after a
 * reboot. Nothing is claimed as scheduled until the alarm is actually registered.
 */
class Scheduler(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    val tasks: Flow<List<ScheduledTask>> = context.scheduleStore.data.map { prefs ->
        prefs[TASKS_KEY]
            ?.let { runCatching { json.decodeFromString<List<ScheduledTask>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun all(): List<ScheduledTask> = tasks.first()

    // ------------------------------------------------------------ scheduling

    /**
     * Registers [task] and returns what actually happened.
     *
     * The exactness of the alarm is reported rather than assumed: from Android 12
     * an app needs a user-granted permission to fire an alarm at a precise minute,
     * and without it Android will batch the alarm with others and deliver it late.
     * A reminder that arrives "sometime around then" is fine; an alarm that does is
     * not, and the user needs to know which one they've got.
     */
    suspend fun add(task: ScheduledTask): SchedulingOutcome {
        val manager = alarms
            ?: return SchedulingOutcome.Failed("This device exposes no alarm service.")
        val at = task.nextTrigger()
            ?: return SchedulingOutcome.Failed("That time has already passed.")

        val exact = canScheduleExact()
        val pending = pendingIntentFor(task)
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // Not silently downgraded — the caller is told, and tells the user.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (t: SecurityException) {
            return SchedulingOutcome.Failed(
                "Android refused the alarm: ${t.message ?: "exact alarms aren't permitted"}"
            )
        }

        val armed = task.copy(triggerAtMillis = at)
        save(all().filterNot { it.id == armed.id } + armed)
        return SchedulingOutcome.Scheduled(armed, exact)
    }

    /** Re-arms an existing task, used after it fires and after a reboot. */
    private suspend fun rearm(task: ScheduledTask): ScheduledTask? {
        val manager = alarms ?: return null
        val at = task.nextTrigger() ?: return null
        val pending = pendingIntentFor(task)
        runCatching {
            if (canScheduleExact()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }.getOrElse { return null }
        return task.copy(triggerAtMillis = at)
    }

    /**
     * Called by the receiver once a task has fired: a repeating task is moved to
     * its next occurrence, a one-shot is removed. Without this a daily alarm rings
     * once and never again, which is the classic way homegrown schedulers fail.
     */
    suspend fun onFired(id: String) {
        val task = all().firstOrNull { it.id == id } ?: return
        if (task.repeat == Repeat.ONCE) {
            save(all().filterNot { it.id == id })
            return
        }
        val next = task.nextTrigger(after = System.currentTimeMillis())
        if (next == null) {
            save(all().filterNot { it.id == id })
        } else {
            val moved = rearm(task.copy(triggerAtMillis = next))
            save(all().map { if (it.id == id) (moved ?: it) else it })
        }
    }

    /** Restores every alarm after a reboot, which clears AlarmManager entirely. */
    suspend fun restoreAll() {
        val restored = all().mapNotNull { task ->
            if (!task.enabled) task else rearm(task)
        }
        save(restored)
    }

    // --------------------------------------------------------------- editing

    /** @return the task that was cancelled, or null if nothing matched. */
    suspend fun cancel(id: String): ScheduledTask? {
        val task = all().firstOrNull { it.id == id } ?: return null
        alarms?.cancel(pendingIntentFor(task))
        save(all().filterNot { it.id == id })
        return task
    }

    /**
     * Cancels by description rather than id, because a person says "cancel my 7am
     * alarm", never "cancel task 4f3a…". Returns null when the phrase is ambiguous
     * or matches nothing — guessing which alarm to delete is not recoverable.
     */
    suspend fun cancelMatching(phrase: String): CancelOutcome {
        val needle = phrase.trim().lowercase()
        if (needle.isEmpty()) return CancelOutcome.NoMatch
        val current = all()
        val hits = current.filter {
            it.label.lowercase().contains(needle) ||
                it.target.lowercase().contains(needle) ||
                it.describe().lowercase().contains(needle)
        }
        return when {
            hits.isEmpty() -> CancelOutcome.NoMatch
            hits.size > 1 -> CancelOutcome.Ambiguous(hits)
            else -> CancelOutcome.Cancelled(cancel(hits.first().id) ?: return CancelOutcome.NoMatch)
        }
    }

    // ------------------------------------------------------------ permission

    /**
     * Whether Android will honour the requested minute exactly.
     *
     * Below Android 12 every alarm is exact. From 12 it needs a permission the user
     * grants on a Settings screen; from 13 an alarm-clock app may hold USE_EXACT_ALARM
     * at install time, which is why this asks the manager rather than inferring
     * from the SDK level.
     */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else alarms?.canScheduleExactAlarms() == true

    /** Opens the screen where the user can allow exact alarms. Never auto-granted. */
    fun openExactAlarmSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- wiring

    private fun pendingIntentFor(task: ScheduledTask): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            // A distinct action per task keeps PendingIntent equality from collapsing
            // two different alarms into one — extras are not part of that comparison.
            action = "com.lain.assistant.FIRE_${task.id}"
            putExtra(ScheduledTaskReceiver.EXTRA_ID, task.id)
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun save(list: List<ScheduledTask>) {
        val ordered = list.sortedBy { it.triggerAtMillis }
        context.scheduleStore.edit { it[TASKS_KEY] = json.encodeToString(ordered) }
    }

    companion object {
        /** Actions that reach another person, and so are held behind confirmation. */
        val OUTWARD_ACTIONS = setOf(TaskAction.CALL, TaskAction.SMS)
    }
}

sealed class SchedulingOutcome {
    /** @param exact false when Android will deliver this late; the user is told. */
    data class Scheduled(val task: ScheduledTask, val exact: Boolean) : SchedulingOutcome()
    data class Failed(val reason: String) : SchedulingOutcome()
}

sealed class CancelOutcome {
    data class Cancelled(val task: ScheduledTask) : CancelOutcome()
    data class Ambiguous(val candidates: List<ScheduledTask>) : CancelOutcome()
    object NoMatch : CancelOutcome()
}
