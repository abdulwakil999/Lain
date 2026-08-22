package com.lain.assistant

import com.lain.assistant.agent.MessageActions
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageActionsTest {

    private fun user(id: String, text: String) =
        ChatMessage(id = id, sender = Sender.USER, text = text)

    private fun lain(id: String, text: String) =
        ChatMessage(id = id, sender = Sender.LAIN, text = text)

    private val transcript = listOf(
        lain("0", "What's up niceo?"),
        user("1", "what's my battery"),
        lain("2", "77%."),
        user("3", "open spotify"),
        lain("4", "Opened Spotify.")
    )

    @Test
    fun `resending a user message re-runs that message`() {
        assertEquals("open spotify", MessageActions.resendTarget(transcript, "3"))
    }

    @Test
    fun `resending a reply re-runs the question behind it`() {
        // Not the reply text: feeding Lain's own answer back as a request produces a
        // confident non-sequitur rather than a second attempt at the question.
        assertEquals("open spotify", MessageActions.resendTarget(transcript, "4"))
        assertEquals("what's my battery", MessageActions.resendTarget(transcript, "2"))
    }

    @Test
    fun `an opening greeting has nothing to re-run`() {
        assertNull(MessageActions.resendTarget(transcript, "0"))
    }

    @Test
    fun `a message that is no longer there resolves to nothing`() {
        // The obvious way to crash this: resend a message that was just deleted.
        assertNull(MessageActions.resendTarget(transcript, "does-not-exist"))
        assertNull(MessageActions.resendTarget(emptyList(), "1"))
    }

    @Test
    fun `a blank message is not resent`() {
        val messages = listOf(user("1", "   "), lain("2", "Sorry?"))
        assertNull(MessageActions.resendTarget(messages, "1"))
        assertNull(MessageActions.resendTarget(messages, "2"))
    }

    @Test
    fun `the nearest preceding question wins, not the first`() {
        val messages = listOf(
            user("1", "first thing"),
            lain("2", "done"),
            user("3", "second thing"),
            lain("4", "also done")
        )
        assertEquals("second thing", MessageActions.resendTarget(messages, "4"))
    }
}
