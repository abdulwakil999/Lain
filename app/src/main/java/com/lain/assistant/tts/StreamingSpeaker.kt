package com.lain.assistant.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Speaks a reply while it is still being generated.
 *
 * Waiting for the complete response before speaking means time-to-first-audio
 * equals total generation time — several seconds of silence on a free model,
 * which in voice mode is the entire experience. Prose arrives in order, though,
 * and a finished sentence is safe to read aloud without knowing what follows.
 *
 * So text is buffered until a clause boundary, handed to the engine, and the next
 * chunk queues behind it. The user hears the first sentence about as soon as the
 * model has produced it, and generation continues underneath.
 *
 * Two things this deliberately does not do:
 *
 *  - **Split mid-thought.** Chunking on every comma would make the delivery stutter
 *    and mangle the prosody, so it breaks on sentence enders and only falls back to
 *    a softer boundary once a chunk has grown long enough that waiting is worse.
 *  - **Speak concurrently.** Utterances go through a channel consumed by one
 *    coroutine, so they play in the order written even though they were produced
 *    asynchronously.
 */
class StreamingSpeaker(
    private val engine: TtsEngine,
    private val scope: CoroutineScope,
    private val onSpeakingChanged: (Boolean) -> Unit
) {

    companion object {
        /**
         * The first utterance is allowed to be short.
         *
         * Time-to-first-audio is what voice mode is judged on, and a reply often opens
         * with a brief sentence ("62%.", "Opening Spotify."). Holding that back until
         * 40 characters had accumulated meant waiting for a second sentence that might
         * never come — the delay this class exists to remove.
         */
        private const val MIN_FIRST_CHUNK = 12

        /**
         * Later chunks want to be longer: once audio is already playing, smooth
         * delivery matters more than shaving milliseconds, and short utterances
         * stitched together sound clipped.
         */
        private const val MIN_CHUNK = 40

        /** Past this, break at the softest boundary available rather than keep buffering. */
        private const val SOFT_BREAK_AFTER = 160

        private val SENTENCE_END = charArrayOf('.', '!', '?', '\n')
        private val SOFT_END = charArrayOf(',', ';', ':', '—')
    }

    private val pending = StringBuilder()
    private var queue: Channel<String>? = null
    private var worker: Job? = null
    private var spoken = 0

    /** Opens a new utterance stream. Any previous one is abandoned. */
    fun begin() {
        stop()
        pending.setLength(0)
        spoken = 0
        val channel = Channel<String>(Channel.UNLIMITED)
        queue = channel
        worker = scope.launch {
            onSpeakingChanged(true)
            try {
                for (chunk in channel) {
                    // Suspends until this chunk finishes, which is what keeps the
                    // delivery in order without any explicit synchronisation.
                    engine.speak(chunk)
                }
            } finally {
                onSpeakingChanged(false)
            }
        }
    }

    /** Feeds a fragment of freshly generated text. Speaks whatever is now complete. */
    fun offer(delta: String) {
        val channel = queue ?: return
        pending.append(delta)
        while (true) {
            val cut = findBoundary(pending, first = spoken == 0) ?: break
            val chunk = pending.substring(0, cut).trim()
            pending.delete(0, cut)
            if (chunk.isNotEmpty()) {
                spoken++
                channel.trySend(chunk)
            }
        }
    }

    /** No more text is coming: flush the tail and let the queue drain. */
    fun finish() {
        val channel = queue ?: return
        val tail = pending.toString().trim()
        pending.setLength(0)
        if (tail.isNotEmpty()) channel.trySend(tail)
        channel.close()
        queue = null
    }

    /** True once at least one chunk has been handed to the engine. */
    val hasStarted: Boolean get() = spoken > 0

    fun stop() {
        queue?.close()
        queue = null
        worker?.cancel()
        worker = null
        engine.stop()
        pending.setLength(0)
        onSpeakingChanged(false)
    }

    /**
     * @return the index to cut at (exclusive), or null if nothing is speakable yet.
     */
    private fun findBoundary(buffer: CharSequence, first: Boolean): Int? {
        val minimum = if (first) MIN_FIRST_CHUNK else MIN_CHUNK
        if (buffer.length < minimum) return null

        // Scan backwards so a chunk carries as much complete text as possible; a longer
        // utterance sounds more natural than several short ones stitched together.
        for (i in buffer.length - 1 downTo minimum - 1) {
            val c = buffer[i]
            if (c in SENTENCE_END) {
                // "3.5" and "e.g." are not sentence ends. Requiring whitespace (or the
                // end of what we have) after the mark rules out most of that.
                val next = buffer.getOrNull(i + 1)
                if (next == null || next.isWhitespace()) return i + 1
            }
        }

        if (buffer.length >= SOFT_BREAK_AFTER) {
            for (i in buffer.length - 1 downTo minimum - 1) {
                if (buffer[i] in SOFT_END) return i + 1
            }
            // A very long clause with no punctuation at all: break on a word boundary
            // rather than let the buffer grow unbounded while the user waits.
            val lastSpace = buffer.lastIndexOf(" ")
            if (lastSpace >= minimum) return lastSpace + 1
        }
        return null
    }
}

private fun CharSequence.getOrNull(index: Int): Char? = if (index in indices) this[index] else null
