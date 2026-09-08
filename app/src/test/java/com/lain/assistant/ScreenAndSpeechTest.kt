package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LainName
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import com.lain.assistant.agent.WhenParser
import com.lain.assistant.automation.Messengers
import com.lain.assistant.automation.WakeWordService
import com.lain.assistant.data.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bugs this batch was reported for, held down by tests.
 *
 * Each one is a thing Lain said or did on a real phone, not a hypothetical: she
 * claimed she could not schedule a message, she claimed she could neither hear nor
 * speak, she said WhatsApp was not installed on a phone that had it, and asking her
 * to read the screen took a network round trip.
 */
class ScreenAndSpeechTest {

    private fun local(message: String): LocalIntent? =
        (FastRouter.route(message) as? Route.Local)?.intent

    // ------------------------------------------------------------ scheduling

    @Test
    fun `a nightly line is scheduled, not refused`() {
        // The reported failure, verbatim. It reached a model, which answered that it
        // had no clock and could not schedule anything.
        val intent = local("tell me goodnight everyday at 11 pm")
        assertTrue("routed to $intent", intent is LocalIntent.Schedule)
    }

    @Test
    fun `everyday written as one word still repeats daily`() {
        val parsed = WhenParser.parse("tell me goodnight everyday at 11 pm")
        assertNotNull(parsed)
        assertEquals(Repeat.DAILY, parsed!!.repeat)
        assertEquals("tell me goodnight", parsed.remainder)
    }

    @Test
    fun `a one-off spoken line is scheduled too`() {
        assertTrue(local("tell me goodnight at 11 pm") is LocalIntent.Schedule)
        assertTrue(local("say happy birthday to me at 9 am") is LocalIntent.Schedule)
    }

    @Test
    fun `a plain remark with a number is not a schedule`() {
        // "at" plus a number is not enough; there has to be a command in front of it.
        assertFalse(local("the meeting was at 3 and it dragged") is LocalIntent.Schedule)
    }

    // ------------------------------------------------------------ the screen

    @Test
    fun `asking what is on screen never leaves the phone`() {
        listOf(
            "what's on screen",
            "what does this say",
            "what is the screen showing",
            "read the screen",
            "read out the screen to me",
            "describe the screen"
        ).forEach {
            assertTrue("\"$it\" routed to ${FastRouter.route(it)}", local(it) is LocalIntent.ReadScreen)
        }
    }

    @Test
    fun `talking about a person is not a screen read`() {
        // The loose version of this caught "what is he saying", which is conversation.
        assertFalse(local("what is he saying about the deal") is LocalIntent.ReadScreen)
        assertFalse(local("what page of the book are you on") is LocalIntent.ReadScreen)
    }

    @Test
    fun `tapping a named control is resolved locally`() {
        assertEquals("send", (local("tap the send button") as? LocalIntent.TapText)?.label)
        assertEquals("continue", (local("press continue for me") as? LocalIntent.TapText)?.label)
        assertEquals("accept", (local("click accept") as? LocalIntent.TapText)?.label)
    }

    @Test
    fun `navigation keys are not treated as on-screen labels`() {
        assertFalse(local("press back") is LocalIntent.TapText)
        assertFalse(local("go home") is LocalIntent.TapText)
        // Nothing left after the filler words is not a request this can serve.
        assertFalse(local("tap the button") is LocalIntent.TapText)
    }

    // ------------------------------------------------------------- her name

    @Test
    fun `being addressed is recognised through a mis-hearing`() {
        assertTrue(LainName.isAddressed("lane i need help"))
        assertTrue(LainName.isAddressed("lane, the wifi is off"))
        assertTrue(LainName.isAddressed("yes lane"))
        assertTrue(LainName.isAddressed("wait lane"))
    }

    @Test
    fun `ordinary sentences are still left alone`() {
        // Every one of these is a word the user actually said, and rewriting it would
        // change their message before the model ever saw it.
        assertEquals("the rain is heavy today", LainName.normaliseHeard("the rain is heavy today"))
        assertEquals("rain check on that", LainName.normaliseHeard("rain check on that"))
        assertEquals("stay in the fast lane", LainName.normaliseHeard("stay in the fast lane"))
        assertEquals("draw a straight line", LainName.normaliseHeard("draw a straight line"))
    }

    @Test
    fun `her name alone wakes her`() {
        assertTrue(WakeWordService.matchesWakePhrase("lane"))
        assertTrue(WakeWordService.matchesWakePhrase("lane open whatsapp"))
        assertFalse(WakeWordService.matchesWakePhrase("what lane am I in"))
        assertFalse(WakeWordService.matchesWakePhrase("the rain is heavy"))
    }

    // ------------------------------------------------------------ messengers

    @Test
    fun `whatsapp is more than one package`() {
        val whatsapp = Messengers.byKey("whatsapp")
        assertNotNull(whatsapp)
        // The bug: com.whatsapp was hardcoded, so a phone with only WhatsApp Business
        // was told WhatsApp wasn't installed.
        assertTrue(whatsapp!!.packages.contains("com.whatsapp"))
        assertTrue(whatsapp.packages.contains("com.whatsapp.w4b"))
    }

    @Test
    fun `a whatsapp link carries the message, a telegram link cannot`() {
        // Uri.parse is an Android call and returns null on the JVM, so the link
        // itself can't be built here; what matters is the promise each app makes.
        assertEquals(Messengers.Compose.PREFILLED, Messengers.byKey("whatsapp")!!.compose)
        assertEquals(Messengers.Compose.PREFILLED, Messengers.byKey("signal")!!.compose)

        // Telegram has no documented way to prefill, so it is marked as needing the
        // text typed — which is what lets the caller say so instead of claiming a send.
        assertEquals(Messengers.Compose.TYPED, Messengers.byKey("telegram")!!.compose)
    }
}
