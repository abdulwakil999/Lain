package com.lain.assistant

import com.lain.assistant.agent.WhenParser
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Getting the hour right, which is the one thing an alarm has to do.
 *
 * Every case here failed before: "o'clock" and a bare "for 2" parsed as nothing at
 * all, so the local path gave up and a model was left to invent the hour — and it
 * invented morning. Saying "pm" did not help, because with "o'clock" between the
 * number and the marker the pm was never read.
 */
class AlarmTimeTest {

    private fun hourOf(phrase: String): Int? = WhenParser.parse(phrase)?.let {
        Calendar.getInstance().apply { timeInMillis = it.triggerAtMillis }.get(Calendar.HOUR_OF_DAY)
    }

    private fun minuteOf(phrase: String): Int? = WhenParser.parse(phrase)?.let {
        Calendar.getInstance().apply { timeInMillis = it.triggerAtMillis }.get(Calendar.MINUTE)
    }

    @Test
    fun `o'clock is a time, however it is spelled`() {
        listOf("set an alarm for 2 o'clock", "set an alarm for 2 oclock", "alarm for 2 o clock")
            .forEach { assertNotNull("\"$it\" parsed as nothing", hourOf(it)) }
    }

    @Test
    fun `pm is honoured even when it is not touching the number`() {
        // The failure verbatim: said pm, got the morning.
        assertEquals(14, hourOf("set an alarm for 2 o'clock pm"))
        assertEquals(14, hourOf("set an alarm for 2 oclock pm"))
        assertEquals(14, hourOf("set an alarm for 2 pm"))
        assertEquals(19, hourOf("wake me at 7 o'clock in the pm"))
    }

    @Test
    fun `am is honoured the same way`() {
        assertEquals(2, hourOf("set an alarm for 2 o'clock am"))
        assertEquals(7, hourOf("set an alarm for 7 am"))
        // An explicit marker beats a loose one elsewhere in the sentence.
        assertEquals(7, hourOf("set an alarm for 7am tonight"))
    }

    @Test
    fun `for is a preposition too, not only at`() {
        assertEquals(hourOf("set an alarm at 2 pm"), hourOf("set an alarm for 2 pm"))
        assertNotNull(hourOf("set an alarm for 2"))
        assertNotNull(hourOf("remind me for seven"))
    }

    @Test
    fun `a bare hour with no marker still reads sociably`() {
        // "Set an alarm for 2" is the afternoon; nobody means two in the morning.
        assertEquals(14, hourOf("set an alarm for 2"))
        assertEquals(7, hourOf("set an alarm for 7"))
    }

    @Test
    fun `minutes survive all of it`() {
        assertEquals(14, hourOf("set an alarm for 2:30 pm"))
        assertEquals(30, minuteOf("set an alarm for 2:30 pm"))
        assertEquals(0, minuteOf("set an alarm for 2 o'clock"))
    }

    @Test
    fun `a number that is not a time is still not a time`() {
        // The preposition rule is what keeps this from being a 7am alarm.
        assertNull(hourOf("remind me about room 7"))
        assertNull(hourOf("there are 12 of them"))
    }

    @Test
    fun `midnight and noon are not shifted by the sociable rule`() {
        assertEquals(0, hourOf("set an alarm for midnight"))
        assertEquals(12, hourOf("remind me at noon"))
    }
}
