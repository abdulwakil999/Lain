package com.lain.assistant

import com.lain.assistant.agent.LainName
import com.lain.assistant.automation.WakeWordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The false rewrites matter more than the corrections: this edits the user's own
 * words before the model sees them, so a loose rule would silently change what
 * they said.
 */
class LainNameTest {

    @Test
    fun `a greeting to Lain is corrected however it was heard`() {
        listOf("hello lane", "hey lane", "hi laine", "hello line", "hello lang", "ok lane")
            .forEach { heard ->
                assertTrue("$heard -> ${LainName.normaliseHeard(heard)}",
                    LainName.normaliseHeard(heard).contains("Lain"))
            }
    }

    @Test
    fun `an instruction addressed to her is corrected`() {
        assertEquals("Lain open whatsapp", LainName.normaliseHeard("lane open whatsapp"))
        assertEquals("Lain what's the time", LainName.normaliseHeard("lane what's the time"))
    }

    @Test
    fun `a trailing address is corrected`() {
        assertEquals("open whatsapp Lain", LainName.normaliseHeard("open whatsapp lane"))
    }

    @Test
    fun `punctuation survives the rewrite`() {
        assertEquals("hello Lain,", LainName.normaliseHeard("hello lane,"))
    }

    @Test
    fun `an ordinary use of the word is left alone`() {
        // The rule that earns its keep. These are the user's actual words.
        assertEquals("draw a straight line", LainName.normaliseHeard("draw a straight line"))
        assertEquals("stay in the fast lane", LainName.normaliseHeard("stay in the fast lane"))
        assertEquals("the rain in spain", LainName.normaliseHeard("the rain in spain"))
        assertEquals("text him about the line", LainName.normaliseHeard("text him about the line"))
    }

    @Test
    fun `the name alone is the name`() {
        assertEquals("Lain", LainName.normaliseHeard("lane"))
    }

    @Test
    fun `an already correct transcript is untouched`() {
        assertEquals("hello Lain", LainName.normaliseHeard("hello Lain"))
        assertEquals("", LainName.normaliseHeard(""))
    }

    @Test
    fun `the name is respelled for the synthesiser and nowhere else`() {
        // An English voice reads L-A-I-N as "Lane". The name is meant to sound like
        // "Line", so the string on its way into the synthesiser is respelled — and
        // only that string. Every screen still says Lain.
        assertEquals("Hello, I'm Line.", LainName.forSpeech("Hello, I'm Lain."))
        assertEquals("That's Line's job.", LainName.forSpeech("That's Lain's job."))
    }

    @Test
    fun `the respelling never touches ordinary English`() {
        // "lain" is also the past participle of "lie", and a global replace would
        // turn somebody's own words into nonsense on its way to being read aloud.
        assertEquals("Plainly obvious", LainName.forSpeech("Plainly obvious"))
        assertEquals("it had lain there for years", LainName.forSpeech("it had lain there for years"))
        assertEquals("he has lain low", LainName.forSpeech("he has lain low"))
        assertEquals("The blame lain elsewhere", LainName.forSpeech("The blame lain elsewhere"))
        assertEquals("Chamberlain", LainName.forSpeech("Chamberlain"))
    }

    @Test
    fun `the wake phrase matches through a mis-hearing`() {
        assertTrue(WakeWordService.matchesWakePhrase("hello lane"))
        assertTrue(WakeWordService.matchesWakePhrase("hey Lain"))
        assertTrue(WakeWordService.matchesWakePhrase("hi laine"))
        assertFalse(WakeWordService.matchesWakePhrase("what lane am I in"))
        assertFalse(WakeWordService.matchesWakePhrase("hello there"))
    }
}
