package com.lain.assistant.automation

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The calendar already on the phone, read and written directly.
 *
 * Through `CalendarContract`, which is the provider every calendar app on Android
 * syncs into — so this reaches Google Calendar, Outlook and whatever the
 * manufacturer shipped, without an account, an OAuth flow or a network call. It
 * works in flight mode on whatever has already synced.
 *
 * Reading and writing are separate permissions and are asked for separately. "What's
 * on today" is a far smaller thing to grant than "put things in my diary", and an
 * app that demands both to answer the first is an app people decline.
 */
class CalendarAccess(private val context: Context) {

    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun canWrite(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * What is on between two instants.
     *
     * Queried through `Instances` rather than `Events`, which matters for anything
     * repeating: `Events` holds one row for "every Tuesday" and would report a weekly
     * standup as a single meeting years ago. `Instances` expands the rule into the
     * actual occurrences, which is what "what's on today" means.
     */
    fun events(fromMillis: Long, toMillis: Long, limit: Int = 20): ToolResult {
        if (!canRead()) {
            return ToolResult.fail(
                FailureKind.PERMISSION,
                "Reading the calendar needs the calendar permission, which hasn't been granted."
            )
        }

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()

        val columns = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        return runCatching {
            val found = mutableListOf<String>()
            context.contentResolver.query(
                uri, columns, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && found.size < limit) {
                    val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    val begin = cursor.getLong(1)
                    val allDay = cursor.getInt(4) == 1
                    val where = cursor.getString(3)?.takeIf { it.isNotBlank() }
                    val whenText = if (allDay) dayFormat.format(Date(begin)) else timeFormat.format(Date(begin))
                    found += listOfNotNull(whenText, title, where?.let { "at $it" }).joinToString(" — ")
                }
            }
            if (found.isEmpty()) ToolResult.ok("Nothing in the calendar for that period.")
            else ToolResult.ok(found.joinToString("\n"))
        }.getOrElse {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't read the calendar: ${it.message}")
        }
    }

    /**
     * Adds an event, and reads it back before saying it worked.
     *
     * The read-back is not ceremony. A content-provider insert can return a URI on a
     * device whose calendar sync then rejects the row, and "it's in your diary" for
     * something that is not there is precisely the failure this project keeps
     * finding. If it cannot be read back, that is reported.
     */
    fun addEvent(title: String, startMillis: Long, durationMinutes: Int, location: String?): ToolResult {
        if (!canWrite()) {
            return ToolResult.fail(
                FailureKind.PERMISSION,
                "Adding to the calendar needs the calendar permission, which hasn't been granted."
            )
        }
        if (title.isBlank()) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "An event needs a title.")
        }

        val calendarId = defaultCalendarId()
            ?: return ToolResult.fail(
                FailureKind.CAPABILITY_UNAVAILABLE,
                "No writable calendar on this phone — there's no account for it to go in."
            )

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title.take(200))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + durationMinutes.coerceIn(5, 1440) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it.take(200)) }
        }

        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ToolResult.fail(FailureKind.TOOL_FAILURE, "The calendar refused the event.")
            val id = ContentUris.parseId(uri)

            val stored = context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(CalendarContract.Events.TITLE), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }

            if (stored == null) {
                ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "Wrote the event but couldn't read it back, so I can't say it saved."
                )
            } else {
                ToolResult.ok("Added \"$title\" on ${dayFormat.format(Date(startMillis))} at ${timeFormat.format(Date(startMillis))}.")
            }
        }.getOrElse {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't add the event: ${it.message}")
        }
    }

    /**
     * The calendar an event should land in.
     *
     * The primary, visible, writable one. Picking the first writable row regardless
     * would happily file a dentist appointment in a subscribed holidays calendar the
     * user never looks at.
     */
    private fun defaultCalendarId(): Long? = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.VISIBLE
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { cursor ->
            var fallback: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val primary = cursor.getInt(1) == 1
                val visible = cursor.getInt(2) == 1
                if (primary && visible) return@use id
                if (fallback == null && visible) fallback = id
            }
            fallback
        }
    }.getOrNull()

    /**
     * Hands the event to a calendar app instead of writing it.
     *
     * The route when the write permission is refused: the user still gets their
     * event, they just press save themselves. An assistant that can only do a thing
     * with a permission you declined should offer the version that needs none.
     */
    fun composeEvent(title: String, startMillis: Long, durationMinutes: Int, location: String?): ToolResult =
        runCatching {
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                .putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    startMillis + durationMinutes.coerceIn(5, 1440) * 60_000L
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            location?.takeIf { it.isNotBlank() }
                ?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            context.startActivity(intent)
            ToolResult.ok("Opened the calendar with \"$title\" filled in — press save.")
        }.getOrElse {
            ToolResult.fail(FailureKind.APP_UNAVAILABLE, "No calendar app would take the event.")
        }

    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
}
