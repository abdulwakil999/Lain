package com.lain.assistant

import com.lain.assistant.agent.Deliberation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The false positives matter as much as the catches: wrongly flagging a real answer
 * costs the user an extra round trip and can end with Lain refusing to say something
 * she had already worked out.
 */
class DeliberationTest {

    private fun stalled(text: String) = Deliberation.isThinkingOutLoud(text, actedThisTurn = false)

    @Test
    fun `narrating a plan instead of acting is caught`() {
        assertTrue(
            stalled(
                "Okay, so the user wants me to send a message to Moyo. I should first look up the " +
                    "contact to get their number, and then I will use the messaging tool to send it."
            )
        )
    }

    @Test
    fun `repeated statements of intent with nothing done are caught`() {
        assertTrue(
            stalled(
                "I need to open WhatsApp first. Then I will find the chat with Ade, and I'm going to " +
                    "type the message into the box before sending it."
            )
        )
    }

    @Test
    fun `a report of what happened is not deliberation`() {
        assertFalse(stalled("Sent to Moyo on WhatsApp: \"on my way\". It's in the thread."))
        assertFalse(stalled("Opened Call of Duty. It's in the foreground now."))
    }

    @Test
    fun `a short answer is never deliberation`() {
        assertFalse(stalled("77%."))
        assertFalse(stalled("Done."))
        assertFalse(stalled("It's 4:14 pm."))
    }

    @Test
    fun `asking the user a question is a legitimate reply`() {
        assertFalse(
            stalled(
                "There's more than one Moyo in your contacts — MOYO and MoyOma. I need to know which " +
                    "one you meant before I send anything. Which is it?"
            )
        )
    }

    @Test
    fun `reflection after doing something is a summary, not a stall`() {
        // Identical words, but a tool ran. Having actually done something turns the
        // same sentence from a stall into an explanation of what just happened.
        val text = "The user wants me to text Moyo, so I need to look up the contact and " +
            "then I'm going to open the thread."
        assertTrue(Deliberation.isThinkingOutLoud(text, actedThisTurn = false))
        assertFalse(Deliberation.isThinkingOutLoud(text, actedThisTurn = true))
    }

    @Test
    fun `past-tense narration is left alone`() {
        // Conservative on purpose: describing work in the past tense is how a reply
        // explains itself, and flagging it would suppress real answers.
        assertFalse(
            stalled("I needed to look up the contact first, and then open the chat before sending it.")
        )
    }

    @Test
    fun `a plain explanation that reports a limit is not deliberation`() {
        assertFalse(
            stalled(
                "Android hasn't let apps switch Wi-Fi themselves since Android 10, so I can't flip it " +
                    "for you. I've put the system panel on screen instead."
            )
        )
    }

    @Test
    fun `empty and whitespace are not flagged`() {
        assertFalse(stalled(""))
        assertFalse(stalled("    "))
    }
}
