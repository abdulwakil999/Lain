package com.lain.assistant

import com.lain.assistant.agent.LainName
import com.lain.assistant.automation.WakeWordService
import com.lain.assistant.voice.VoiceState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice pipeline's rules, held down where they can be checked without a phone.
 *
 * The parts that need real hardware — the energy detector, the recogniser, the
 * synthesiser — are exercised by hand against VOICE_TESTING.md. What is testable
 * here is every decision made in pure Kotlin: what counts as being called, what
 * part of a wake phrase is a command, and what the synthesiser is handed.
 */
class VoiceSystemTest {

    // ------------------------------------------------------------ waking her

    @Test
    fun `her name is the wake word, not a fixed phrase`() {
        listOf(
            "hello Lain",
            "hey Lain",
            "hi Lain",
            "sup Lain",
            "yo Lain",
            "okay Lain",
            "Lain",
            "Lain open whatsapp",
            "Lain, what's the time"
        ).forEach {
            assertTrue("\"$it\" should wake her", WakeWordService.matchesWakePhrase(it))
        }
    }

    @Test
    fun `it survives the recogniser mishearing the name`() {
        // No recogniser has heard of "Lain". These are what they actually return.
        listOf("hello lane", "hey laine", "sup layne", "hi line").forEach {
            assertTrue("\"$it\" should wake her", WakeWordService.matchesWakePhrase(it))
        }
    }

    @Test
    fun `ordinary speech does not wake her`() {
        // The cost of being wrong here is an assistant that interrupts conversations,
        // so these matter more than the ones above.
        listOf(
            "what lane am I in",
            "the rain is heavy",
            "stay in the fast lane",
            "draw a straight line",
            "take a rain check",
            "hello there"
        ).forEach {
            assertFalse("\"$it\" must not wake her", WakeWordService.matchesWakePhrase(it))
        }
    }

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
            VoiceState.WAKE_WORD_DETECTED,
            VoiceState.LISTENING_FOR_COMMAND,
            VoiceState.PROCESSING,
            VoiceState.EXECUTING,
            VoiceState.SPEAKING
        ).forEach { assertTrue("$it should be transient", it.isTransient) }

        listOf(VoiceState.IDLE, VoiceState.LISTENING_FOR_WAKE_WORD, VoiceState.ERROR)
            .forEach { assertFalse("$it should not be transient", it.isTransient) }
    }

    @Test
    fun `only the listening states claim the microphone`() {
        assertTrue(VoiceState.LISTENING_FOR_WAKE_WORD.holdsMicrophone)
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

    // ------------------------------------------------------------- the pre-roll

    @Test
    fun `the pre-roll keeps the newest audio, oldest first`() {
        // What the recogniser is handed. If the wrap is wrong the samples come back
        // rotated, which a recogniser hears as nothing and a user reads as "the wake
        // word doesn't work".
        val ring = com.lain.assistant.voice.AudioRing(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6), 6)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), ring.snapshot())
    }

    @Test
    fun `a part-filled pre-roll returns only what it has`() {
        val ring = com.lain.assistant.voice.AudioRing(8)
        ring.write(shortArrayOf(7, 8, 9), 3)
        assertArrayEquals(shortArrayOf(7, 8, 9), ring.snapshot())
    }

    @Test
    fun `writes that wrap several times still land in order`() {
        val ring = com.lain.assistant.voice.AudioRing(3)
        repeat(4) { ring.write(shortArrayOf(1, 2, 3, 4, 5), 5) }
        // The last three samples written, in the order they were spoken.
        assertArrayEquals(shortArrayOf(3, 4, 5), ring.snapshot())
    }

    @Test
    fun `clearing drops everything`() {
        val ring = com.lain.assistant.voice.AudioRing(4)
        ring.write(shortArrayOf(1, 2, 3), 3)
        ring.clear()
        assertArrayEquals(shortArrayOf(), ring.snapshot())
    }

    // -------------------------------------------------------------- the voice

    @Test
    fun `the UI name and the spoken name are different strings`() {
        // The requirement in one assertion: what is displayed stays "Lain", what is
        // synthesised becomes "Line".
        assertEquals("Lain", LainName.CANONICAL)
        assertEquals("Line is ready.", LainName.forSpeech("Lain is ready."))
    }
}
