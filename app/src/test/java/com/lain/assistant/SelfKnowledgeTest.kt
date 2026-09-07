package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.IdentityQuestion
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Replies
import com.lain.assistant.agent.Route
import com.lain.assistant.data.ModelCapabilityRegistry
import com.lain.assistant.data.Provider
import com.lain.assistant.network.RequestTuning
import com.lain.assistant.network.TokenBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Lain knows about herself, and what that costs.
 *
 * The rule these enforce: a question about the app is answered by the app. Sending
 * "what can you do" to a language model produced an invented feature list when it
 * worked and nothing at all on a plane, and neither is acceptable for the first
 * question almost everybody asks.
 */
class SelfKnowledgeTest {

    private fun local(message: String): LocalIntent? =
        (FastRouter.route(message) as? Route.Local)?.intent

    private fun identity(message: String): IdentityQuestion? =
        (local(message) as? LocalIntent.Identity)?.question

    // --------------------------------------------------------- describing herself

    @Test
    fun `she describes herself without a network, twelve ways`() {
        assertTrue(
            "only ${Replies.capabilities.size} self-descriptions",
            Replies.capabilities.size >= 12
        )
    }

    @Test
    fun `every self-description names where she came from`() {
        Replies.capabilities.forEach { line ->
            val text = line.text
            assertTrue(
                "no developer in: ${text.take(60)}…",
                text.contains("Poopy Butthole") || text.contains("Lewa·dev")
            )
        }
    }

    @Test
    fun `the family is named across the descriptions`() {
        val all = Replies.capabilities.joinToString(" ") { it.text }
        listOf("Professor Poopy Butthole", "Lewa·dev", "Lewa Coder", "Lewa Therapist").forEach {
            assertTrue("\"$it\" is never mentioned", all.contains(it))
        }
    }

    @Test
    fun `each description is a real answer, not a category label`() {
        // A one-liner is what sent people away thinking she does very little.
        Replies.capabilities.forEach {
            assertTrue("too short: ${it.text}", it.text.length > 180)
        }
    }

    @Test
    fun `asking what she is never reaches a model`() {
        listOf(
            "what can you do",
            "what are you",
            "tell me about yourself",
            "describe yourself",
            "what are your capabilities",
            "what are your features",
            "introduce yourself",
            "what can you help me with"
        ).forEach {
            assertEquals("\"$it\" left the phone", IdentityQuestion.CAPABILITIES, identity(it))
        }
    }

    @Test
    fun `she is equally clear about what she cannot do`() {
        assertEquals(IdentityQuestion.LIMITS, identity("what can't you do"))
        assertEquals(IdentityQuestion.LIMITS, identity("what are your limits"))
        assertTrue(Replies.limits.isNotEmpty())
        // Real platform limits, not modesty.
        val all = Replies.limits.joinToString(" ") { it.text }
        assertTrue(all.contains("permission"))
    }

    @Test
    fun `the sibling apps are answered from the app, not invented`() {
        assertEquals(IdentityQuestion.LEWA_CODER, identity("what can lewa coder do"))
        assertEquals(IdentityQuestion.LEWA_THERAPIST, identity("what is lewa therapist"))
        assertEquals(IdentityQuestion.MAKER, identity("who is professor poopy butthole"))
    }

    // ------------------------------------------------------------- naming a song

    @Test
    fun `naming the playing song is its own request`() {
        listOf("what song is this", "what's playing", "shazam this", "who sings this")
            .forEach { assertTrue("\"$it\" routed to ${local(it)}", local(it) is LocalIntent.IdentifyMusic) }
    }

    @Test
    fun `asking to play something is still playback`() {
        // The two are one word apart and mean opposite things.
        assertTrue(local("play something") !is LocalIntent.IdentifyMusic)
        assertTrue(local("play burna boy on spotify") !is LocalIntent.IdentifyMusic)
    }

    // -------------------------------------------------------------- token budget

    private val strong = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
    private val weak = ModelCapabilityRegistry.forModel("vendor/small-9b:free", Provider.OPENROUTER)

    @Test
    fun `asking for brevity lowers the ceiling and asking for detail raises it`() {
        val plain = TokenBudget.forRequest(RequestTuning.ANSWER, strong, "how does this work")
        val brief = TokenBudget.forRequest(RequestTuning.ANSWER, strong, "briefly, how does this work")
        val full = TokenBudget.forRequest(RequestTuning.ANSWER, strong, "explain how this works in full detail")

        assertTrue("brief should be shorter", brief.maxTokens < plain.maxTokens)
        assertTrue("detailed should be longer", full.maxTokens > plain.maxTokens)
    }

    @Test
    fun `a request for twenty things gets room for twenty things`() {
        val listy = TokenBudget.forRequest(RequestTuning.ANSWER, strong, "give me 20 examples")
        assertTrue(listy.maxTokens > RequestTuning.ANSWER.maxTokens)
    }

    @Test
    fun `truncation doubles the room rather than nudging it`() {
        val first = TokenBudget.forRequest(RequestTuning.TOOL_STEP, strong, "do the thing")
        val second = TokenBudget.afterTruncation(first, strong)
        assertEquals(first.maxTokens * 2, second.maxTokens)
    }

    @Test
    fun `a small model is never asked for more than it can give`() {
        // A 32k free model handed an 8000-token ceiling truncates anyway, and paying a
        // round trip to discover that is the whole point of clamping.
        val huge = TokenBudget.forRequest(RequestTuning.STUDY, weak, "write the entire program in full detail")
        assertTrue("asked a small model for ${huge.maxTokens}", huge.maxTokens <= weak.contextTokens / 4)
    }

    @Test
    fun `a spoken reply stays short however the question is phrased`() {
        // It arrived through a microphone and leaves through a speaker; "in detail"
        // does not mean four hundred tokens read aloud.
        val spoken = TokenBudget.forRequest(RequestTuning.SPOKEN, strong, "explain that in full detail")
        assertEquals(RequestTuning.SPOKEN.maxTokens, spoken.maxTokens)
    }
}
