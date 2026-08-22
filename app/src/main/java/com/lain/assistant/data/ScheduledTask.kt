package com.lain.assistant.data

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** What Lain should do when a scheduled task comes due. */
@Serializable
enum class TaskAction {
    /** A notification. Quiet, dismissable, no sound beyond the channel's default. */
    REMIND,

    /** A full-screen alarm inside Lain: sound, vibration, snooze, dismiss. */
    ALARM,

    /**
     * A full-screen prompt with a Call button.
     *
     * Deliberately not an automatic dial. "Call mama every day at seven" means the
     * user wants to be *put through* at seven — not for their phone to ring someone
     * from a pocket while they're in a meeting. A call with nobody on this end is
     * worse than no call, and it is not reversible once the other person's phone
     * rings. So Lain brings the call up, ready, and one tap places it.
     */
    CALL,

    /** Sends the text. Authored and confirmed when the task was created. */
    SMS,

    /** Brings an app to the front. */
    OPEN_APP
}

/** How often a task comes back. */
@Serializable
enum class Repeat {
    ONCE, DAILY, WEEKDAYS, WEEKENDS, WEEKLY;

    val label: String
        get() = when (this) {
            ONCE -> "once"
            DAILY -> "every day"
            WEEKDAYS -> "every weekday"
            WEEKENDS -> "every weekend"
            WEEKLY -> "every week"
        }
}

/**
 * One thing Lain will do at a time in the future.
 *
 * Stored rather than held in memory, and re-armed after a reboot, because an alarm
 * that silently stops existing when the phone restarts is worse than no alarm — the
 * user only finds out by oversleeping.
 */
@Serializable
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    /** What the user called it: "call mama", "wake up", "take the tablets". */
    val label: String,
    val action: TaskAction,
    /** Contact name/number for CALL and SMS, app name for OPEN_APP, unused otherwise. */
    val target: String = "",
    /** The SMS body. Nothing else uses it. */
    val payload: String = "",
    val triggerAtMillis: Long,
    val repeat: Repeat = Repeat.ONCE,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** "07:00 every weekday — call mama" */
    fun describe(): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(triggerAtMillis))
        val day = if (repeat == Repeat.ONCE) {
            " on " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(triggerAtMillis))
        } else {
            " ${repeat.label}"
        }
        val what = when (action) {
            TaskAction.REMIND -> "remind you: $label"
            TaskAction.ALARM -> "alarm: $label"
            TaskAction.CALL -> "call $target"
            TaskAction.SMS -> "text $target"
            TaskAction.OPEN_APP -> "open $target"
        }
        return "$time$day — $what" + if (!enabled) " (off)" else ""
    }

    /**
     * The next time this should fire after [after].
     *
     * Returns null for a one-shot that has already passed, which is how a finished
     * task gets cleaned up rather than re-arming into the past.
     */
    fun nextTrigger(after: Long = System.currentTimeMillis()): Long? {
        if (triggerAtMillis > after) return triggerAtMillis
        if (repeat == Repeat.ONCE) return null

        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        // Step forward from the original time so the minute never drifts, however
        // many days have passed since the task was created.
        var guard = 0
        while (cal.timeInMillis <= after || !matchesPattern(cal)) {
            cal.add(Calendar.DAY_OF_YEAR, if (repeat == Repeat.WEEKLY) 7 else 1)
            if (++guard > 400) return null
        }
        return cal.timeInMillis
    }

    private fun matchesPattern(cal: Calendar): Boolean {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        return when (repeat) {
            Repeat.ONCE, Repeat.DAILY, Repeat.WEEKLY -> true
            Repeat.WEEKDAYS -> !weekend
            Repeat.WEEKENDS -> weekend
        }
    }
}
