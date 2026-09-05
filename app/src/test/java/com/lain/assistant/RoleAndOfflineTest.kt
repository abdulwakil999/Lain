package com.lain.assistant

import com.lain.assistant.agent.DeveloperGate
import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.Language
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Replies
import com.lain.assistant.agent.Route
import com.lain.assistant.data.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The developer role, the stand-down, and having no signal.
 */
class RoleAndOfflineTest {

    @Before
    @After
    fun clearGates() = DeveloperGate.disarm()

    // ----------------------------------------------------------------- role

    @Test
    fun `there are more than twenty ways to address the role`() {
        assertTrue("only ${Replies.praises.size}", Replies.praises.size >= 22)
        assertEquals(
            "a praise repeats",
            Replies.praises.size,
            Replies.praises.map { it.text }.toSet().size
        )
    }

    @Test
    fun `the two given examples are in there`() {
        val all = Replies.praises.map { it.text.lowercase() }
        assertTrue(all.any { it.contains("lion king") })
        assertTrue(all.any { it.contains("king kong") })
    }

    @Test
    fun `every praise is short enough to drop into a sentence`() {
        // These get addressed *to* someone mid-reply. A paragraph would not survive it.
        Replies.praises.forEach {
            assertTrue("\"${it.text}\" is too long to use as an address", it.text.length <= 40)
        }
    }

    // ---------------------------------------------------------- stand down

    @Test
    fun `standing the role down takes two steps`() {
        val phrase = "potato chips"

        val first = FastRouter.route(phrase)
        assertTrue("the phrase wasn't recognised", first is Route.Local)
        assertEquals(LocalIntent.StandDownRequest, (first as Route.Local).intent)

        // Asked, then confirmed. One unlucky sentence must not do it.
        DeveloperGate.askToStandDown()
        assertTrue(DeveloperGate.isStandingDown)
        assertTrue(DeveloperGate.confirmStandDown(phrase))

        DeveloperGate.askToStandDown()
        assertFalse("anything else confirmed it", DeveloperGate.confirmStandDown("yes"))
    }

    @Test
    fun `the confirmation is consumed either way`() {
        DeveloperGate.askToStandDown()
        DeveloperGate.confirmStandDown("no")
        assertFalse("the gate stayed open after a refusal", DeveloperGate.isStandingDown)
    }

    @Test
    fun `the stand-down replies never echo the phrase back`() {
        // Echoing it would put it in the transcript, which is the one place it must not be.
        (Replies.standDownAsk + Replies.standDownDone + Replies.standDownKept).forEach {
            assertFalse(
                "\"${it.text}\" repeats the phrase",
                it.text.contains("potato", ignoreCase = true)
            )
        }
    }

    // -------------------------------------------------------------- offline

    @Test
    fun `there are twelve ways to say there is no connection`() {
        assertEquals(12, Replies.offline.size)
        assertEquals(12, Replies.offline.map { it.text }.toSet().size)
    }

    @Test
    fun `every offline line says what is actually wrong`() {
        Replies.offline.forEach {
            assertTrue(
                "\"${it.text}\" doesn't explain it",
                it.text.contains("internet connection") && it.text.contains("brain")
            )
        }
    }

    @Test
    fun `every offline line carries a Spanish insult`() {
        val insults = listOf("necio", "tonto", "señal", "nada")
        Replies.offline.forEach { line ->
            assertTrue(
                "\"${line.text}\" has no insult in it",
                insults.any { line.text.contains(it, ignoreCase = true) }
            )
        }
    }

    @Test
    fun `the Spanish in an offline line is marked as Spanish`() {
        // Or the synthesiser reads the insult with an English voice, which is not an
        // insult in Spanish, it is two English words.
        val mixed = Replies.offline.filter { it.segments.size > 1 }
        assertTrue("none of them are segmented", mixed.isNotEmpty())
        mixed.forEach { line ->
            assertTrue(line.segments.any { it.language == Language.Tag.SPANISH })
        }
    }

    // ---------------------------------------------------------------- keys

    @Test
    fun `every provider says where to get a key`() {
        Provider.entries.forEach {
            assertTrue("${it.displayName} has no key page", it.keyPageUrl.startsWith("https://"))
        }
    }
}
