package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import com.lain.assistant.agent.WhenParser
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A command with a time in it belongs to later, not to now.
 *
 * "Open WhatsApp at 2:26 pm" opened WhatsApp immediately: scheduling was recognised
 * only by opener words like "remind me", and an app launch containing a number has
 * none of them. The signal is the time, not the vocabulary.
 */
class ScheduledCommandTest {

    private fun intent(message: String): LocalIntent? =
        (FastRouter.route(message) as? Route.Local)?.intent

    @Test
    fun `a command with a future time is scheduled, not run now`() {
        listOf(
            "open whatsapp at 2 26 pm",
            "open whatsapp at 2:26 pm",
            "call ade at 6 pm",
            "text mum at 9 pm",
            "play burna boy at 8 pm"
        ).forEach { ask ->
            val found = intent(ask)
            assertTrue("\"$ask\" ran immediately instead of scheduling", found is LocalIntent.Schedule)
        }
    }

    @Test
    fun `the same command with no time still runs now`() {
        assertTrue(intent("open whatsapp") is LocalIntent.OpenApp)
        assertTrue(intent("call ade") is LocalIntent.Call)
    }

    @Test
    fun `a countdown is a timer, not a schedule`() {
        // "in five minutes" reads better as a timer, and timer() phrases it that way.
        val found = intent("call ade in 5 minutes")
        assertTrue("a countdown became a schedule", found !is LocalIntent.Schedule)
    }

    @Test
    fun `a phone number is not a time`() {
        // The preposition rule is what stops digits in a number becoming an hour.
        assertTrue(intent("call 07700900123") !is LocalIntent.Schedule)
    }

    @Test
    fun `a space where the colon should be is still a time`() {
        // What speech recognition produces, constantly.
        val parsed = WhenParser.parse("open whatsapp at 2 26 pm")
        assertNotNull("\"2 26 pm\" parsed as nothing", parsed)
        val at = Calendar.getInstance().apply { timeInMillis = parsed!!.triggerAtMillis }
        assertEquals(14, at.get(Calendar.HOUR_OF_DAY))
        assertEquals(26, at.get(Calendar.MINUTE))
    }

    @Test
    fun `the label does not keep the digits of the time`() {
        // Left in, the reminder reads "open whatsapp 2 26 pm" forever after.
        val parsed = WhenParser.parse("open whatsapp at 2 26 pm")
        assertTrue(
            "the time survived into the label: \"${parsed!!.remainder}\"",
            !parsed.remainder.contains("26")
        )
    }
}
