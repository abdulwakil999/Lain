package com.lain.assistant

import com.lain.assistant.tts.StreamingSpeaker
import com.lain.assistant.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chunker decides how soon voice mode starts talking and whether it sounds
 * like a person or a stuttering robot. Both properties are checked: that a first
 * utterance is dispatched well before the reply is finished, and that chunks break
 * where a speaker would pause.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingSpeakerTest {

    private class RecordingEngine : TtsEngine {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) {
            spoken += text
        }
        override fun stop() = Unit
    }

    /** Feeds text the way a model emits it — a few characters at a time. */
    private fun feed(speaker: StreamingSpeaker, text: String, chunkSize: Int = 6) {
        text.chunked(chunkSize).forEach { speaker.offer(it) }
    }

    @Test
    fun `speaking starts before the reply is complete`() = runTest(StandardTestDispatcher()) {
        val engine = RecordingEngine()
        val speaker = StreamingSpeaker(engine, CoroutineScope(coroutineContext)) {}
        speaker.begin()

        val reply = "Your battery is at sixty percent. It should last the rest of the afternoon. " +
            "Charging now would get you to full in about an hour."

        // Feed only the first sentence and check something was already dispatched —
        // this is the whole point: audio starts while generation continues.
        feed(speaker, "Your battery is at sixty percent. ")
        assertTrue("nothing was queued after a complete sentence", speaker.hasStarted)

        feed(speaker, reply.removePrefix("Your battery is at sixty percent. "))
        speaker.finish()
        advanceUntilIdle()

        assertEquals(reply.trim(), engine.spoken.joinToString(" ").trim())
    }

    @Test
    fun `chunks break at sentence ends, not mid-thought`() = runTest(StandardTestDispatcher()) {
        val engine = RecordingEngine()
        val speaker = StreamingSpeaker(engine, CoroutineScope(coroutineContext)) {}
        speaker.begin()

        feed(speaker, "The first thing is done. The second thing is also done. And a third.")
        speaker.finish()
        advanceUntilIdle()

        assertTrue("expected more than one utterance", engine.spoken.size > 1)
        // Every chunk but the last should end on a sentence boundary.
        engine.spoken.dropLast(1).forEach { chunk ->
            assertTrue("chunk did not end at a sentence boundary: \"$chunk\"", chunk.trimEnd().last() in ".!?")
        }
    }

    @Test
    fun `a decimal point is not treated as the end of a sentence`() = runTest(StandardTestDispatcher()) {
        val engine = RecordingEngine()
        val speaker = StreamingSpeaker(engine, CoroutineScope(coroutineContext)) {}
        speaker.begin()

        // "3.5" would split into "…version 3." / "5 is out" if the boundary check only
        // looked for a full stop — audibly wrong, and a common way to get this subtly bad.
        feed(speaker, "The build you want is version 3.5 and it is out now already.")
        speaker.finish()
        advanceUntilIdle()

        assertTrue(
            "split inside a decimal: ${engine.spoken}",
            engine.spoken.none { it.trimEnd().endsWith("3.") }
        )
    }

    @Test
    fun `no text produces no utterances`() = runTest(StandardTestDispatcher()) {
        val engine = RecordingEngine()
        val speaker = StreamingSpeaker(engine, CoroutineScope(coroutineContext)) {}
        speaker.begin()
        speaker.finish()
        advanceUntilIdle()
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `a short reply is still spoken in full`() = runTest(StandardTestDispatcher()) {
        val engine = RecordingEngine()
        val speaker = StreamingSpeaker(engine, CoroutineScope(coroutineContext)) {}
        speaker.begin()
        // Below the minimum chunk size, so it only leaves the buffer on finish().
        feed(speaker, "62%.")
        speaker.finish()
        advanceUntilIdle()
        assertEquals(listOf("62%."), engine.spoken)
    }
}
