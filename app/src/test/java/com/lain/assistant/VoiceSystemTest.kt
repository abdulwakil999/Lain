package com.lain.assistant

import com.lain.assistant.agent.LainName
import com.lain.assistant.agent.Misheard
import com.lain.assistant.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice pipeline's rules, held down where they can be checked without a phone.
 *
 * The parts that need real hardware — the recogniser, the synthesiser — are
 * exercised by hand against VOICE_TESTING.md. What is testable here is every
 * decision made in pure Kotlin: what the recogniser's output is corrected into,
 * what part of an addressed sentence is the command, and what the synthesiser is
 * handed.
 */
class VoiceSystemTest {

    // -------------------------------------------------- the command inside it

    @Test
    fun `a wake phrase carrying an instruction is not asked for twice`() {
        assertEquals("open whatsapp", LainName.commandAfterName("hey lane open whatsapp"))
        assertEquals("what's the time", LainName.commandAfterName("Lain what's the time"))
        assertEquals(
            "turn on the torch",
            LainName.commandAfterName("sup lane turn on the torch")
        )
    }

    @Test
    fun `a bare address carries no command`() {
        assertNull(LainName.commandAfterName("hello lane"))
        assertNull(LainName.commandAfterName("lane"))
        assertNull(LainName.commandAfterName("hey Lain"))
        // One stray word after the name is far more often a mis-hearing than an
        // instruction, and acting on it is worse than asking.
        assertNull(LainName.commandAfterName("lane um"))
    }

    @Test
    fun `an address at the end still yields the command`() {
        assertEquals("open whatsapp", LainName.commandAfterName("open whatsapp lane"))
    }

    // --------------------------------------------------------- the state machine

    @Test
    fun `every state a user can be stuck in is marked transient`() {
        // The watchdog reads this, so a state missing from it is a state Lain can sit
        // in forever — which is the exact failure the watchdog exists to prevent.
        listOf(
            VoiceState.LISTENING_FOR_COMMAND,
            VoiceState.PROCESSING,
            VoiceState.EXECUTING,
            VoiceState.SPEAKING
        ).forEach { assertTrue("$it should be transient", it.isTransient) }

        listOf(VoiceState.IDLE, VoiceState.ERROR)
            .forEach { assertFalse("$it should not be transient", it.isTransient) }
    }

    @Test
    fun `only the listening state claims the microphone`() {
        assertTrue(VoiceState.LISTENING_FOR_COMMAND.holdsMicrophone)
        assertFalse(VoiceState.SPEAKING.holdsMicrophone)
        assertFalse(VoiceState.PROCESSING.holdsMicrophone)
        assertFalse(VoiceState.IDLE.holdsMicrophone)
    }

    @Test
    fun `every state has something to show the user`() {
        VoiceState.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
        }
        assertEquals("Thinking…", VoiceState.PROCESSING.label)
        assertEquals("Working…", VoiceState.EXECUTING.label)
        assertEquals("Speaking…", VoiceState.SPEAKING.label)
        assertEquals("Voice unavailable", VoiceState.ERROR.label)
    }

    // -------------------------------------------------------------- the voice

    @Test
    fun `what is shown is Lain and what is said is Lane`() {
        // The requirement in one place: the screen keeps the name, the synthesiser
        // gets the pronunciation. Every engine calls forSpeech, so this is the only
        // place the two can disagree.
        assertEquals("Lain", LainName.CANONICAL)
        assertEquals("Lane is ready.", LainName.forSpeech("Lain is ready."))
    }

    @Test
    fun `she says her name the same way however it was written`() {
        // "Sometimes she says it differently" is what a partial rewrite sounds like,
        // so every spelling of the name that is actually her has to land on one
        // sound. Possessive, shouted, mid-sentence, twice in a row.
        assertEquals("Lane's battery tool", LainName.forSpeech("Lain's battery tool"))
        assertEquals("LANE!", LainName.forSpeech("LAIN!"))
        assertEquals("Ask Lane. Lane knows.", LainName.forSpeech("Ask Lain. Lain knows."))
        assertEquals("\"Lane\", she said.", LainName.forSpeech("\"Lain\", she said."))
    }

    @Test
    fun `ordinary English that only looks like her name is left alone`() {
        // The past participle of "lie". Rewriting it would put a road into somebody's
        // quoted text, which is worse than the mispronunciation being fixed.
        assertEquals("he had lain there", LainName.forSpeech("he had lain there"))
        assertEquals("Chamberlain", LainName.forSpeech("Chamberlain"))
        assertEquals("Plainly", LainName.forSpeech("Plainly"))
    }

    // ------------------------------------------------- the rest of the vocabulary

    @Test
    fun `names the recogniser has never heard are put back`() {
        assertEquals("switch to Claude", Misheard.correct("switch to claud"))
        assertEquals("use DeepSeek", Misheard.correct("use deep seek"))
        assertEquals("open WhatsApp", Misheard.correct("open whats app"))
        assertEquals("play the Qur'an", Misheard.correct("play the koran"))
        assertEquals("ask ChatGPT", Misheard.correct("ask chat gbt"))
    }

    @Test
    fun `a real word is only a model name when the sentence is about models`() {
        // "cloud" is the one that started this: it is what every recogniser returns
        // for Claude, and it is also an ordinary word.
        assertEquals("switch to Claude", Misheard.correct("switch to cloud"))
        assertEquals("use Claude instead", Misheard.correct("use cloud instead"))
        assertEquals("which model is Claude", Misheard.correct("which model is cloud"))
    }

    @Test
    fun `ordinary sentences survive the correction`() {
        // The failure this guards against is worse than the one it fixes: rewriting
        // words somebody actually said.
        listOf(
            "upload the photos to the cloud",
            "is there a cloud in the sky",
            "back up my files to cloud storage",
            "the weather is cloud and rain",
            "the judge declared a mistrial",
            "a lama spat at me at the zoo",
            "use the cloud drive"
        ).forEach {
            assertEquals("\"$it\" must survive untouched", it, Misheard.correct(it))
        }
    }

    @Test
    fun `a name is not rewritten inside a longer word`() {
        assertEquals("Salamanca", Misheard.correct("Salamanca"))
        assertEquals("clouds gathered", Misheard.correct("clouds gathered"))
    }
}
