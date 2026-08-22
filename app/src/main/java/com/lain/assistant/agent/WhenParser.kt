package com.lain.assistant.agent

import com.lain.assistant.data.Repeat
import java.util.Calendar

/**
 * Turns "2:30", "half seven tomorrow", "every weekday at 7am", "in 20 minutes"
 * into a timestamp and a repeat rule.
 *
 * This is here rather than in a prompt because it is the part a free model gets
 * wrong most expensively: an alarm set for the wrong day is worse than no alarm,
 * and "2:30" quietly becoming 14:30 at two in the morning is exactly the kind of
 * mistake a language model makes confidently. Clock arithmetic is deterministic,
 * so it is done deterministically, and the caller reads the resolved time back to
 * the user so a misparse is visible before it matters.
 */
object WhenParser {

    data class Parsed(
        val triggerAtMillis: Long,
        val repeat: Repeat,
        /** What was left of the phrase once the time words were taken out. */
        val remainder: String
    )

    private val WORD_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "midnight" to 0, "noon" to 12, "midday" to 12
    )

    private val DAY_NAMES = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY
    )

    /**
     * @param now injectable so the parsing is testable without waiting for the clock
     *            to reach an interesting moment.
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): Parsed? {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return null

        relative(t, now)?.let { return it }

        val repeat = repeatRule(t)
        val clock = clockTime(t) ?: return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, clock.hour)
            set(Calendar.MINUTE, clock.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val namedDay = DAY_NAMES.entries.firstOrNull { t.contains(it.key) }?.value
        when {
            // An explicit weekday wins: "at 7 on Friday" means Friday, not today.
            namedDay != null -> {
                var guard = 0
                while (cal.get(Calendar.DAY_OF_WEEK) != namedDay || cal.timeInMillis <= now) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    if (++guard > 14) return null
                }
            }
            t.contains("tomorrow") -> cal.add(Calendar.DAY_OF_YEAR, 1)
            // A bare time that has already gone today means the next one, which is
            // what a person means by "wake me at six" said at eleven at night.
            cal.timeInMillis <= now -> cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return Parsed(cal.timeInMillis, repeat, strip(t))
    }

    // ------------------------------------------------------------- relative

    /** "in 20 minutes", "in an hour and a half", "in 2 hours". */
    private fun relative(t: String, now: Long): Parsed? {
        val m = Regex("\\bin\\s+(a|an|\\d+)\\s*(second|sec|minute|min|hour|hr|day)s?\\b").find(t) ?: return null
        val raw = m.groupValues[1]
        val amount = if (raw == "a" || raw == "an") 1 else raw.toIntOrNull() ?: return null
        var millis = when {
            m.groupValues[2].startsWith("s") -> amount * 1_000L
            m.groupValues[2].startsWith("m") -> amount * 60_000L
            m.groupValues[2].startsWith("d") -> amount * 86_400_000L
            else -> amount * 3_600_000L
        }
        if (t.contains("and a half")) {
            millis += when {
                m.groupValues[2].startsWith("h") -> 1_800_000L
                m.groupValues[2].startsWith("d") -> 43_200_000L
                else -> 30_000L
            }
        }
        if (millis <= 0) return null
        return Parsed(now + millis, Repeat.ONCE, strip(t))
    }

    // ---------------------------------------------------------------- clock

    private data class Clock(val hour: Int, val minute: Int)

    private fun clockTime(t: String): Clock? {
        // 7:30, 07:30, 2.30, with optional am/pm.
        Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(t)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: return null
            if (hour > 23 || minute > 59) return null
            return Clock(applyMeridiem(hour, m.groupValues[3], t), minute)
        }
        // "9 pm", "for 9am" — an explicit am/pm is its own proof that the number is
        // a time, so no preceding "at" is needed.
        Regex("\\b(\\d{1,2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b").find(t)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return null
            if (hour > 23) return null
            return Clock(applyMeridiem(hour, m.groupValues[2], t), 0)
        }
        // "at 7", with no am/pm — the "at" is required so a bare number in
        // "remind me about room 7" isn't mistaken for a time.
        Regex("\\bat\\s+(\\d{1,2})\\b").find(t)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return null
            if (hour > 23) return null
            return Clock(applyMeridiem(hour, "", t), 0)
        }
        // "at seven", "at midnight", "at noon".
        WORD_NUMBERS.entries.firstOrNull { Regex("\\b(at\\s+)?${it.key}\\b").containsMatchIn(t) }?.let { (word, hour) ->
            if (word == "midnight" || word == "noon" || word == "midday") return Clock(hour, 0)
            if (!t.contains("at $word")) return null
            return Clock(applyMeridiem(hour, "", t), 0)
        }
        return null
    }

    /**
     * Resolves a bare hour to a 24-hour one.
     *
     * With no am/pm the sensible reading is the sociable one: "wake me at 7" is
     * the morning, "set an alarm for 10" said in the evening is still ten o'clock
     * tonight. Hours 1–6 with no qualifier are read as afternoon/evening, because
     * "meet at 3" almost never means three in the morning.
     */
    private fun applyMeridiem(hour: Int, marker: String, whole: String): Int {
        val pm = marker.startsWith("p") || whole.contains("evening") || whole.contains("tonight") ||
            whole.contains("afternoon")
        val am = marker.startsWith("a") || whole.contains("morning")
        return when {
            hour == 12 && am -> 0
            hour == 12 -> 12
            pm && hour < 12 -> hour + 12
            am -> hour
            hour in 1..6 -> hour + 12
            else -> hour
        }
    }

    // --------------------------------------------------------------- repeat

    private fun repeatRule(t: String): Repeat = when {
        t.contains("every weekday") || t.contains("weekdays") || t.contains("every work day") ||
            t.contains("monday to friday") -> Repeat.WEEKDAYS
        t.contains("every weekend") || t.contains("weekends") -> Repeat.WEEKENDS
        t.contains("every week") || t.contains("weekly") ||
            DAY_NAMES.keys.any { t.contains("every $it") } -> Repeat.WEEKLY
        t.contains("every day") || t.contains("everyday") || t.contains("daily") ||
            t.contains("each day") -> Repeat.DAILY
        else -> Repeat.ONCE
    }

    // ------------------------------------------------------------ remainder

    private val NOISE = listOf(
        "set an alarm for", "set a alarm for", "set alarm for", "set an alarm", "set alarm",
        "wake me up at", "wake me at", "wake me up in", "wake me in", "wake me",
        "remind me to", "remind me about", "remind me", "reminder to", "reminder",
        "schedule", "every weekday", "every weekend", "every week", "every day", "everyday",
        "each day", "daily", "weekly", "weekdays", "weekends", "tomorrow", "tonight",
        "this evening", "in the morning", "in the evening", "at", "on", "for", "please"
    )

    /** The phrase with the scheduling words removed, so what's left is the label. */
    fun strip(text: String): String {
        var out = text.lowercase()
        out = out.replace(Regex("\\bin\\s+(a|an|\\d+)\\s*(second|sec|minute|min|hour|hr|day)s?\\b"), " ")
        out = out.replace(Regex("\\b\\d{1,2}[:.]\\d{2}\\s*(am|pm|a\\.m\\.|p\\.m\\.)?"), " ")
        out = out.replace(Regex("\\b\\d{1,2}\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b"), " ")
        for (day in DAY_NAMES.keys) out = out.replace(Regex("\\bevery\\s+$day\\b"), " ").replace(Regex("\\b$day\\b"), " ")
        for (noise in NOISE.sortedByDescending { it.length }) out = out.replace(Regex("\\b${Regex.escape(noise)}\\b"), " ")
        out = out.replace(Regex("\\b\\d{1,2}\\b"), " ")
        return out.replace(Regex("\\s+"), " ").trim().trim('.', ',')
    }
}
