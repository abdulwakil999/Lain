package com.lain.assistant

import com.lain.assistant.agent.Deliberation
import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.IdentityQuestion
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply in the screenshot, and the questions that should never have reached a
 * model to produce it.
 */
class MonologueAndIdentityTest {

    /** Close to verbatim from the build that shipped it. */
    private val screenshotReply = """
        Here's a thinking process:

        1.  **Analyze User Input:** User asks "what's my name"
        2.  **Check Context:** I have a system prompt that says the user goes by 'Abdulwakil'.
        3.  **Determine Response:** The user is asking for their name. I should answer directly.
        4.  **Formulate Output:** Just state the name. I'll keep it minimal.

        Abdulwakil.
    """.trimIndent()

    @Test
    fun `a declared thinking preamble is separated from the answer`() {
        val split = Deliberation.split(screenshotReply)
        assertEquals("Abdulwakil.", split.answer)
        assertNotNull(split.working)
        assertTrue(split.working!!.contains("Analyze User Input"))
    }

    @Test
    fun `a preamble with no answer under it yields no answer at all`() {
        // Better to say plainly that nothing happened than to show the plan as a reply.
        val split = Deliberation.split(
            "Here's a thinking process:\n\n1. The user wants X\n2. I should call the tool\n3. Let me do that"
        )
        assertNull(split.answer)
        assertNotNull(split.working)
    }

    @Test
    fun `an ordinary answer passes through untouched`() {
        val split = Deliberation.split("Abdulwakil.")
        assertEquals("Abdulwakil.", split.answer)
        assertNull(split.working)
    }

    @Test
    fun `a long genuine answer is not mistaken for working`() {
        val essay = "Android stopped letting apps flip Wi-Fi in Android 10. " +
            "That was deliberate, and no permission brings it back."
        assertEquals(essay, Deliberation.split(essay).answer)
    }

    @Test
    fun `a preamble is caught by the stall check on any route`() {
        assertTrue(Deliberation.isThinkingOutLoud(screenshotReply, actedThisTurn = false))
    }

    // -------------------------------------------------------------- identity

    private fun local(input: String): LocalIntent? =
        (FastRouter.route(input) as? Route.Local)?.intent

    @Test
    fun `asking your own name never reaches a model`() {
        listOf("what's my name", "whats my name", "what is my name", "who am i", "say my name")
            .forEach { phrase ->
                val intent = local(phrase)
                assertTrue("\"$phrase\" went to the model: $intent", intent is LocalIntent.Identity)
                assertEquals(IdentityQuestion.USER_NAME, (intent as LocalIntent.Identity).question)
            }
    }

    @Test
    fun `asking Lain's name and the app name are local`() {
        assertEquals(
            IdentityQuestion.LAIN_NAME,
            (local("what's your name") as LocalIntent.Identity).question
        )
        assertEquals(
            IdentityQuestion.LAIN_NAME,
            (local("who are you") as LocalIntent.Identity).question
        )
        assertEquals(
            IdentityQuestion.APP_NAME,
            (local("what app is this") as LocalIntent.Identity).question
        )
    }

    @Test
    fun `screen off is local and is not confused with data off`() {
        assertTrue(local("turn off my screen") is LocalIntent.LockScreen)
        assertTrue(local("lock the phone") is LocalIntent.LockScreen)
        // "turn off mobile data" must stay a toggle, not lock the screen.
        assertTrue(local("turn off mobile data") is LocalIntent.SystemToggle)
    }

    @Test
    fun `restart and shutdown are recognised but never acted on alone`() {
        assertEquals(true, (local("restart my phone") as LocalIntent.Power).restart)
        assertEquals(false, (local("shut down my phone") as LocalIntent.Power).restart)
        // Bare "restart" could mean the app, the phone or a song — still matched, and
        // still only ever raises the system menu for the user to press.
        assertTrue(local("restart") is LocalIntent.Power)
    }

    @Test
    fun `closing Lain is local`() {
        assertTrue(local("close yourself") is LocalIntent.CloseSelf)
        assertTrue(local("exit lain") is LocalIntent.CloseSelf)
    }

    @Test
    fun `a real question about someone else's name still reaches the model`() {
        // "what's Ade's name" is not a profile lookup.
        assertTrue(local("what's ade's name") !is LocalIntent.Identity)
    }
}
