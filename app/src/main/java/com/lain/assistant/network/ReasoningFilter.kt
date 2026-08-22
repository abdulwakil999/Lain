package com.lain.assistant.network

/**
 * Strips a model's internal reasoning out of the text a user sees or hears.
 *
 * Reasoning arrives three different ways, and all three have to be handled
 * because every free tool-capable model on OpenRouter exposes it:
 *
 *  1. **A separate field** — `delta.reasoning` / `delta.reasoning_content`.
 *     Handled at the source: the request asks the provider not to send it, and
 *     the parsers read only `content`, so a separate field never reaches here.
 *  2. **Inline tags** — `<think>…</think>` and friends, emitted inside `content`
 *     by models whose provider doesn't split the channels out.
 *  3. **Harmony channels** — the `<|channel|>analysis … <|channel|>final` format
 *     gpt-oss uses, where the answer is one labelled section among several.
 *
 * This class handles 2 and 3, as defence in depth: asking a provider not to send
 * reasoning is a request, not a guarantee, and it is the provider that decides.
 *
 * Two design constraints, both from how badly this fails when done casually:
 *
 *  - **Only known markers.** No general "strip anything in angle brackets" rule,
 *    because a legitimate answer containing `<div>` or a generic type like
 *    `List<String>` would be silently eaten. Every marker here is a real one used
 *    by a real model family.
 *  - **Streaming-safe.** A marker can be split across chunks — `<thi` then
 *    `nk>`. Any tail that could still turn out to be the start of a marker is
 *    held back rather than emitted, so reasoning never escapes through a chunk
 *    boundary. That costs a few characters of latency, never correctness.
 */
class ReasoningFilter {

    companion object {
        /**
         * Marker pairs, as (open, close). Order matters only for overlapping
         * prefixes, of which there are none here.
         */
        val PAIRS = listOf(
            "<think>" to "</think>",
            "<thinking>" to "</thinking>",
            "<reason>" to "</reason>",
            "<reasoning>" to "</reasoning>",
            "<analysis>" to "</analysis>",
            "<scratchpad>" to "</scratchpad>",
            // DeepSeek-R1 style, and models that copy it.
            "<|begin_of_thought|>" to "<|end_of_thought|>",
            "<|thinking|>" to "<|/thinking|>",
            // Harmony (gpt-oss): the analysis and commentary channels are internal;
            // the final channel is the answer. Treating "final" as a closer means
            // everything before it is dropped and everything after is kept.
            "<|channel|>analysis<|message|>" to "<|channel|>final<|message|>",
            "<|channel|>commentary<|message|>" to "<|channel|>final<|message|>"
        )

        val OPENERS = PAIRS.map { it.first }
        val CLOSERS = PAIRS.map { it.second }.distinct()

        /**
         * Structural harmony tokens that carry no content. Stripped wherever they
         * appear so a stray one can't end up on screen.
         */
        val STRUCTURAL = listOf("<|start|>assistant", "<|start|>", "<|end|>", "<|return|>", "<|message|>")

        val LONGEST_MARKER = (OPENERS + CLOSERS + STRUCTURAL).maxOf { it.length }

        /**
         * One-shot filtering for a complete (non-streamed) response.
         *
         * @return the answer with reasoning removed, or null when the response was
         *   nothing but reasoning — which the caller must report rather than
         *   presenting an empty reply as though it were an answer.
         */
        fun clean(text: String): String? {
            val filter = ReasoningFilter()
            val out = filter.push(text) + filter.flush()
            val trimmed = out.trim()
            return when {
                trimmed.isNotEmpty() -> trimmed
                // Nothing survived and nothing was stripped: the reply was just empty.
                !filter.strippedAnything -> text.trim().ifEmpty { null }
                else -> null
            }
        }
    }

    private val buffer = StringBuilder()
    private var insideReasoning = false
    private var closerFor: String? = null

    /** True once any reasoning was actually found and dropped. */
    var strippedAnything = false
        private set

    /** True once any user-facing text has been emitted. */
    var emittedAnything = false
        private set

    /**
     * Feeds one streamed fragment.
     *
     * @return the portion safe to display and speak — possibly empty, which is
     *   normal while the model is mid-thought or mid-marker.
     */
    fun push(delta: String): String {
        buffer.append(delta)
        return drain(flushing = false)
    }

    /**
     * Ends the stream and returns whatever is left.
     *
     * If the stream ends while still inside a reasoning block, the block was never
     * closed — the model's formatting broke. Nothing is emitted in that case:
     * showing the reasoning would be exactly the leak this exists to prevent, and
     * [emittedAnything] lets the caller notice it got nothing and say so honestly
     * rather than presenting a blank reply as an answer.
     */
    fun flush(): String = drain(flushing = true)

    private fun drain(flushing: Boolean): String {
        val out = StringBuilder()

        while (true) {
            if (insideReasoning) {
                val closer = closerFor ?: CLOSERS.first()
                val at = buffer.indexOf(closer)
                if (at >= 0) {
                    // Everything up to and including the closer is internal. Harmony's
                    // "final" closer is itself a channel header, so it goes too.
                    buffer.delete(0, at + closer.length)
                    insideReasoning = false
                    closerFor = null
                    strippedAnything = true
                    continue
                }
                // Still thinking. Drop everything that can't be the start of a closer.
                val keep = if (flushing) 0 else partialMarkerTail(buffer, CLOSERS)
                if (buffer.length > keep) {
                    buffer.delete(0, buffer.length - keep)
                    strippedAnything = true
                }
                break
            }

            val hit = earliestOpener()
            if (hit != null) {
                val (index, opener) = hit
                out.append(buffer, 0, index)
                buffer.delete(0, index + opener.length)
                insideReasoning = true
                closerFor = PAIRS.first { it.first == opener }.second
                strippedAnything = true
                continue
            }

            // No opener in view. Emit everything that cannot be the start of one.
            val keep = if (flushing) 0 else partialMarkerTail(buffer, OPENERS + STRUCTURAL)
            if (buffer.length > keep) {
                out.append(buffer, 0, buffer.length - keep)
                buffer.delete(0, buffer.length - keep)
            }
            break
        }

        val text = stripStructural(out.toString())
        if (text.isNotEmpty()) emittedAnything = true
        return text
    }

    /** The first opening marker present, with its position. */
    private fun earliestOpener(): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (opener in OPENERS) {
            val at = buffer.indexOf(opener)
            if (at >= 0 && (best == null || at < best.first)) best = at to opener
        }
        return best
    }

    /**
     * How many trailing characters must be held back because they could still
     * become a marker once more of the stream arrives.
     *
     * Without this, `<thi` at the end of a chunk would be emitted as visible text
     * and the `nk>` in the next chunk would be treated as ordinary content — so
     * the whole reasoning block would leak.
     */
    private fun partialMarkerTail(text: CharSequence, markers: List<String>): Int {
        val max = minOf(LONGEST_MARKER - 1, text.length)
        for (len in max downTo 1) {
            val tail = text.substring(text.length - len)
            if (markers.any { it.length > len && it.startsWith(tail) }) return len
        }
        return 0
    }

    /** Removes harmony's content-free structural tokens. */
    private fun stripStructural(text: String): String {
        if (text.isEmpty() || '<' !in text) return text
        var result = text
        for (token in STRUCTURAL) {
            if (result.contains(token)) {
                result = result.replace(token, "")
                strippedAnything = true
            }
        }
        return result
    }

}
