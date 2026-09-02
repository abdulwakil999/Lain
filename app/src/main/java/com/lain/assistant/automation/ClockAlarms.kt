package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import java.util.Calendar

/**
 * Alarms, set in the phone's own clock app rather than in a scheduler of Lain's.
 *
 * Lain had her own alarm system, and that was the bug behind "cancelling doesn't
 * cancel". Two alarm systems on one phone means two places an alarm can live and
 * only one of them is the place people look — you cancel it in Lain, her record
 * goes, and the thing still rings, or it rings from the clock app and nothing in
 * Lain can touch it. There is no way to reconcile them, because Android exposes no
 * API to read what alarms exist.
 *
 * `ACTION_SET_ALARM` with `EXTRA_SKIP_UI` is the fix: the clock app registers the
 * alarm and shows nothing, so it appears where the user expects it and Lain is not
 * left on screen. It is the same mechanism Google Assistant uses.
 *
 * Two honest limits, both surfaced rather than hidden. `EXTRA_SKIP_UI` is a request
 * — AOSP Clock honours it, some manufacturer clocks show their UI anyway. And
 * nothing can *read* the alarm list, so Lain cannot confirm an alarm exists, only
 * that the clock app accepted the instruction.
 *
 * Reminders and scheduled tasks stay with [Scheduler]. The clock app can ring; it
 * cannot send a text at six.
 */
class ClockAlarms(private val context: Context) {

    /**
     * @param days [Calendar.MONDAY] and friends, for a repeating alarm. Empty is a
     *   one-off at the next occurrence of that time.
     */
    fun set(hour: Int, minute: Int, label: String, days: List<Int> = emptyList()): ToolResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "$hour:$minute isn't a time.")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label.take(80))
            // The whole point: the clock app takes the alarm without taking the screen.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            val at = "%02d:%02d".format(hour, minute)
            val repeat = if (days.isEmpty()) "" else " every ${days.joinToString(", ") { dayName(it) }}"
            // Says what was done, not that it is definitely there. Android has no way
            // to read the alarm list back, so claiming it is set would be a guess in
            // exactly the place a wrong guess costs someone their morning.
            ToolResult.ok(
                "Told the clock app to set an alarm for $at$repeat. It's in your Clock app, not in " +
                    "Lain — check it there if you want to be sure."
            )
        }.getOrElse {
            ToolResult.fail(
                FailureKind.APP_UNAVAILABLE,
                "No clock app on this phone would take the alarm."
            )
        }
    }

    /**
     * Asks the clock app to dismiss an alarm at a given time.
     *
     * `ACTION_DISMISS_ALARM` arrived in Android 6 and is the only route an app has to
     * an alarm it did not create. It is a dismissal rather than a deletion — on a
     * one-off alarm that amounts to the same thing, and on a repeating one the next
     * occurrence is skipped and the schedule stays. Said plainly, because "cancelled"
     * for an alarm that rings again next Tuesday is the kind of wrong that is only
     * discovered at 7am.
     */
    fun dismiss(hour: Int, minute: Int): ToolResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_IS_PM, hour >= 12)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult.ok(
                "Asked the clock app to dismiss the %02d:%02d alarm. If it repeats, that skips the ".format(hour, minute) +
                    "next one rather than deleting the schedule — Android gives no way to delete an alarm."
            )
        }.getOrElse {
            ToolResult.fail(
                FailureKind.CAPABILITY_UNAVAILABLE,
                "This phone's clock app won't take a dismiss instruction. Opening your alarms instead."
            )
        }
    }

    /** Dismisses whatever is next, for "turn this alarm off" while one is ringing. */
    fun dismissNext(): ToolResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult.ok("Asked the clock app to dismiss the next alarm.")
        }.getOrElse {
            ToolResult.fail(FailureKind.CAPABILITY_UNAVAILABLE, "The clock app wouldn't take it.")
        }
    }

    /**
     * Opens the clock app's alarm list.
     *
     * The answer to "what alarms have I got", because there is no way to read them.
     * Showing the real list beats Lain reciting a list of her own that may not match
     * what will actually ring.
     */
    fun showAll(): ToolResult = runCatching {
        context.startActivity(
            Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ToolResult.ok("Opened your alarms. Android doesn't let me read them, so this is the real list.")
    }.getOrElse {
        ToolResult.fail(FailureKind.APP_UNAVAILABLE, "No clock app would open.")
    }

    private fun dayName(day: Int): String = when (day) {
        Calendar.MONDAY -> "Monday"
        Calendar.TUESDAY -> "Tuesday"
        Calendar.WEDNESDAY -> "Wednesday"
        Calendar.THURSDAY -> "Thursday"
        Calendar.FRIDAY -> "Friday"
        Calendar.SATURDAY -> "Saturday"
        else -> "Sunday"
    }
}
