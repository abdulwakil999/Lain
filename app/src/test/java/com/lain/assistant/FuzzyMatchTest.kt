package com.lain.assistant

import com.lain.assistant.agent.FuzzyMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases here are the ones people actually said out loud and Lain failed on.
 * The negative cases matter more than the positive ones: a matcher loose enough to
 * turn "Mona" into "Moyo" would ring the wrong person, and that is not undoable.
 */
class FuzzyMatchTest {

    private val apps = listOf(
        "Call of Duty", "Chrome", "WhatsApp", "WhatsApp Business", "Spotify",
        "Settings", "Duolingo", "Messenger", "Google Maps", "Clock"
    )

    private fun app(query: String): String? =
        when (val r = FuzzyMatch.best(query, apps) { it }) {
            is FuzzyMatch.Result.Found -> r.hit.value
            is FuzzyMatch.Result.Ambiguous -> r.hits.first().value
            FuzzyMatch.Result.None -> null
        }

    // ------------------------------------------------------------------ apps

    @Test
    fun `a longer spoken name still finds the app`() {
        // The original bug: the query is longer than the label, so `contains` never
        // fired and an app on the home screen came back as "not installed".
        assertEquals("Call of Duty", app("call of duty mobile"))
        assertEquals("Call of Duty", app("call of duty mobile app"))
    }

    @Test
    fun `case never matters`() {
        assertEquals("Spotify", app("SPOTIFY"))
        assertEquals("Spotify", app("SpOtIfY"))
        assertEquals("WhatsApp", app("whatsapp"))
        assertEquals("Call of Duty", app("CALL OF DUTY"))
    }

    @Test
    fun `a partial name finds the app`() {
        assertEquals("Duolingo", app("duo"))
        assertEquals("Google Maps", app("maps"))
    }

    @Test
    fun `an exact label beats a longer one that contains it`() {
        // "whatsapp" must not open WhatsApp Business.
        assertEquals("WhatsApp", app("whatsapp"))
        assertEquals("WhatsApp Business", app("whatsapp business"))
    }

    @Test
    fun `a mis-heard app name still resolves`() {
        assertEquals("Spotify", app("spotifi"))
    }

    @Test
    fun `nonsense matches nothing rather than opening something at random`() {
        assertEquals(null, app("qwertyuiop"))
        assertEquals(null, app("photoshop"))
    }

    // -------------------------------------------------------------- contacts

    private val contacts = listOf("MOYO", "MoyOma", "Mona", "Ade Bello", "Móyò Adé", "mum")

    @Test
    fun `a name in any casing reaches the contact`() {
        assertTrue(FuzzyMatch.score("moyo", "MOYO") >= 1.0)
        assertTrue(FuzzyMatch.score("MOYO", "moyo") >= 1.0)
        assertTrue(FuzzyMatch.score("MoYo", "Moyo") >= 1.0)
    }

    @Test
    fun `a shorter name reaches a longer contact`() {
        assertTrue(FuzzyMatch.score("moyo", "MoyOma") >= FuzzyMatch.PLAUSIBLE)
    }

    @Test
    fun `accents and punctuation are not differences`() {
        assertEquals("moyo ade", FuzzyMatch.normalise("Móyò-Adé"))
        assertTrue(FuzzyMatch.score("moyo ade", "Móyò Adé") >= 1.0)
    }

    @Test
    fun `an exact contact wins over one that merely contains it`() {
        val result = FuzzyMatch.best("moyo", contacts) { it }
        assertTrue(result is FuzzyMatch.Result.Found)
        assertEquals("MOYO", (result as FuzzyMatch.Result.Found).hit.value)
    }

    @Test
    fun `two different people scoring alike is reported, not guessed`() {
        val result = FuzzyMatch.best("moy", listOf("Moyo B", "Moyra C")) { it }
        assertTrue("expected ambiguity, got $result", result is FuzzyMatch.Result.Ambiguous)
    }

    @Test
    fun `a different short name is never a near miss`() {
        // One substitution apart, and two entirely different people. The single-edit
        // fallback is deliberately restricted to longer tokens for exactly this.
        assertTrue(FuzzyMatch.score("mona", "Moyo") < FuzzyMatch.PLAUSIBLE)
        assertTrue(FuzzyMatch.score("moyo", "Mona") < FuzzyMatch.PLAUSIBLE)
    }

    @Test
    fun `word order does not matter`() {
        assertTrue(FuzzyMatch.score("bello ade", "Ade Bello") >= FuzzyMatch.CERTAIN)
    }

    @Test
    fun `initials work when they are unambiguous`() {
        assertTrue(FuzzyMatch.score("cod", "Call of Duty") >= FuzzyMatch.CERTAIN)
    }

    @Test
    fun `blank input matches nothing`() {
        assertEquals(FuzzyMatch.Result.None, FuzzyMatch.best("", contacts) { it })
        assertEquals(FuzzyMatch.Result.None, FuzzyMatch.best("   ", contacts) { it })
        assertEquals(0.0, FuzzyMatch.score("", "Moyo"), 0.0001)
    }

    @Test
    fun `an empty candidate list matches nothing`() {
        assertEquals(FuzzyMatch.Result.None, FuzzyMatch.best("moyo", emptyList<String>()) { it })
    }

    @Test
    fun `the same person listed twice is not an ambiguity`() {
        // Two numbers for one contact is a choice of number, not a choice of person.
        val result = FuzzyMatch.best("moyo", listOf("Moyo", "MOYO")) { it }
        assertTrue(result is FuzzyMatch.Result.Found)
    }
}
