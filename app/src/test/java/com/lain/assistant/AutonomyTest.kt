package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Language
import com.lain.assistant.agent.Replies
import com.lain.assistant.agent.Route
import com.lain.assistant.automation.QuranIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyTest {

    private fun local(input: String): LocalIntent? =
        (FastRouter.route(input) as? Route.Local)?.intent

    // ------------------------------------------------------- conversation

    @Test
    fun `a conversational question takes the cheap path, not the agent loop`() {
        // The bug behind "she overthinks small tasks": these phrases were routed to
        // Route.Model — the full toolbox and up to maxToolRounds network calls — to
        // answer a question that needs no tools at all.
        listOf(
            "why is the sky blue",
            "what do you think of jazz",
            "explain how wifi works",
            "should i learn kotlin or swift",
            "what's the difference between a thread and a coroutine"
        ).forEach {
            assertEquals("\"$it\" took the expensive path", Route.Chat, FastRouter.route(it))
        }
    }

    @Test
    fun `an action request still gets the toolbox`() {
        assertEquals(Route.Model, FastRouter.route("book me a table at the place we discussed"))
    }

    // ------------------------------------------------------------ variety

    @Test
    fun `every local answer has several forms`() {
        listOf(
            "greetings" to Replies.greetings,
            "thanks" to Replies.thanks,
            "how are you" to Replies.howAreYou,
            "goodbyes" to Replies.goodbyes,
            "affirmations" to Replies.affirmations,
            "lain name" to Replies.lainName,
            "app name" to Replies.appName,
            "niceo" to Replies.niceo,
            "capabilities" to Replies.capabilities,
            "user name" to Replies.userName("Ade")
        ).forEach { (label, options) ->
            assertTrue("$label has only ${options.size}", options.size >= 4)
            assertEquals("$label repeats itself", options.size, options.map { it.text }.toSet().size)
        }
    }

    @Test
    fun `a pick avoids the line just used`() {
        val last = Replies.greetings.first().text
        repeat(30) {
            assertTrue(Replies.pick(Replies.greetings, last).text != last)
        }
    }

    @Test
    fun `niceo is explained in plain English by some variants`() {
        // The joke is good, but someone asking what a word means should be able to
        // find out what it means.
        val explaining = Replies.niceo.count {
            it.text.contains("slang", ignoreCase = true) ||
                it.text.contains("means", ignoreCase = true)
        }
        assertTrue("only $explaining variants actually explain it", explaining >= 3)
    }

    @Test
    fun `spanish lines are marked as spanish so they are spoken as spanish`() {
        val spanish = Replies.thanks.first { it.text.startsWith("De nada") }
        assertTrue(spanish.segments.all { it.language == Language.Tag.SPANISH })
    }

    @Test
    fun `a mixed line is split so each half is spoken in its own language`() {
        val mixed = Replies.affirmations.first { it.text == "Vale." }
        assertEquals(Language.Tag.SPANISH, mixed.segments.first().language)

        val twoPart = Replies.thanks.first { it.text.contains("Next?") }
        assertEquals(2, twoPart.segments.size)
        assertEquals(Language.Tag.SPANISH, twoPart.segments[0].language)
        assertEquals(Language.Tag.ENGLISH, twoPart.segments[1].language)
    }

    // -------------------------------------------------------------- qur'an

    @Test
    fun `the whole index is present and correct at the edges`() {
        assertEquals(114, QuranIndex.all.size)
        assertEquals("Al-Fatihah", QuranIndex.byNumber(1)?.name)
        assertEquals("An-Nas", QuranIndex.byNumber(114)?.name)
        assertEquals(286, QuranIndex.byNumber(2)?.ayahCount)
    }

    @Test
    fun `a surah is found by number, name or meaning`() {
        assertEquals(18, QuranIndex.find("18")?.number)
        assertEquals(18, QuranIndex.find("Al-Kahf")?.number)
        assertEquals(18, QuranIndex.find("al kahf")?.number)
        assertEquals(18, QuranIndex.find("surah al-kahf")?.number)
        assertEquals(18, QuranIndex.find("the cave")?.number)
        assertEquals(36, QuranIndex.find("ya-sin")?.number)
        assertEquals(55, QuranIndex.find("ar-rahman")?.number)
    }

    @Test
    fun `nonsense finds no surah rather than a random one`() {
        assertNull(QuranIndex.find(""))
        assertNull(QuranIndex.find("200"))
    }

    @Test
    fun `recitation is requested locally`() {
        val intent = local("recite surah al-kahf")
        assertTrue("got $intent", intent is LocalIntent.Recite)
        assertEquals("al-kahf", (intent as LocalIntent.Recite).surah)

        val withReciter = local("play surah 18 by sudais") as LocalIntent.Recite
        assertEquals("18", withReciter.surah)
        assertEquals("sudais", withReciter.reciter)

        assertTrue((local("stop the recitation") as LocalIntent.Recite).stop)
    }

    @Test
    fun `playing music is not mistaken for recitation`() {
        assertTrue(local("play burna boy on spotify") is LocalIntent.PlayMusic)
    }

    // ------------------------------------------------------------ location

    @Test
    fun `where am I is local`() {
        assertTrue(local("where am i") is LocalIntent.WhereAmI)
        assertTrue(local("what's my location") is LocalIntent.WhereAmI)
    }

    @Test
    fun `a question about somewhere else is not a location read`() {
        assertNotNull(FastRouter.route("where is the nearest pharmacy"))
        assertTrue(local("where is the nearest pharmacy") !is LocalIntent.WhereAmI)
    }
}
