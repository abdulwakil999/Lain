package com.lain.assistant.network

import com.lain.assistant.data.ModelCapabilities
import com.lain.assistant.data.ReasoningTier

/**
 * How much room a reply gets, decided per request rather than fixed.
 *
 * [RequestTuning]'s constants are sensible defaults and were also a ceiling nothing
 * could move. That is wrong in both directions at once: "what's the battery" was
 * allotted 1400 tokens it could never use, and "write out the whole file with the
 * error handling" was cut off at the same 1400 and had to be asked again. A budget
 * that never changes is a budget that is wrong for most requests.
 *
 * Three things move it, in order of confidence:
 *
 *  1. **What was asked.** "Briefly" and "in one line" are the user setting a ceiling
 *     out loud; "in full", "the whole thing", "step by step" are the user removing
 *     one. Both are cheap to read and are the strongest signal available.
 *  2. **How big the answer needs to be.** A request for a list of twenty, a table,
 *     or a file needs room a two-sentence answer does not.
 *  3. **What happened last time.** A reply that hit the ceiling gets more on the
 *     retry, doubling rather than nudging, because a truncated answer is not a
 *     shorter answer — it is a broken one, and creeping up costs another round trip
 *     to find that out again.
 *
 * Everything is clamped to what the model can actually take. Asking a 32k free model
 * for an 8000-token reply is a request it will refuse or truncate anyway, and paying
 * a round trip to discover that is the failure this class exists to avoid.
 */
object TokenBudget {

    /** Nobody's question is served by less than this. */
    private const val FLOOR = 300

    /**
     * Ceiling on any single reply, before the model's own context is considered.
     *
     * Not a cost control — it is a latency one. Past this a free model is almost
     * always repeating itself rather than still answering, and the user is watching
     * a spinner for it.
     */
    private const val CEILING = 8_000

    /** The user asking for less. Obeyed exactly; this is not a hint. */
    private val WANTS_SHORT = Regex(
        "\\b(brief(ly)?|short(ly)?|quick(ly)?|in one line|one line|one sentence|" +
            "tl;?dr|summar(y|ise|ize)|just the answer|keep it short)\\b",
        RegexOption.IGNORE_CASE
    )

    /** The user asking for more. */
    private val WANTS_LONG = Regex(
        "\\b(in full|full(y)?|detail(ed|s)?|thorough(ly)?|comprehensive|complete(ly)?|" +
            "step by step|walk me through|explain (why|how)|everything|entire|whole (file|thing|" +
            "program|script|class)|write out|elaborate|in depth)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Shapes of answer that are structurally large whatever the wording. */
    private val BIG_SHAPE = Regex(
        "\\b(list|table|compare|comparison|pros and cons|outline|plan|essay|itinerary|" +
            "recipe|schedule|breakdown|examples?)\\b",
        RegexOption.IGNORE_CASE
    )

    /** "give me twenty of them" — the number is the size. */
    private val COUNTED = Regex("\\b(\\d{1,3})\\s+(of them|items|ideas|examples|points|ways|reasons|steps|lines)\\b")

    /**
     * The budget for one request.
     *
     * @param base the route's starting point — TOOL_STEP, ANSWER, STUDY, SPOKEN.
     * @param message what the user actually said, which is where most of the signal is.
     */
    fun forRequest(base: RequestTuning, caps: ModelCapabilities, message: String): RequestTuning {
        var tokens = base.maxTokens

        // A spoken answer is two sentences by design. Nothing in the text should talk
        // it upwards — a model reading four hundred tokens aloud is not what anybody
        // meant by "in detail", and the request came through a microphone.
        if (base.maxTokens > RequestTuning.SPOKEN.maxTokens) {
            when {
                WANTS_SHORT.containsMatchIn(message) -> tokens = (tokens * 0.4).toInt()
                WANTS_LONG.containsMatchIn(message) -> tokens = (tokens * 2.5).toInt()
                BIG_SHAPE.containsMatchIn(message) -> tokens = (tokens * 1.6).toInt()
            }
            COUNTED.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { count ->
                // Roughly 40 tokens an item, plus the prose around the list.
                tokens = maxOf(tokens, count.coerceAtMost(100) * 40 + 300)
            }
            // A long question is usually a detailed question.
            if (message.length > 400) tokens = (tokens * 1.3).toInt()
        }

        return base.copy(maxTokens = clamp(tokens, caps))
    }

    /**
     * More room after a reply was cut off.
     *
     * Doubling rather than nudging: the ceiling was wrong by an unknown amount, and
     * each guess costs a whole request to test. Two tries at double should clear
     * almost anything that was going to fit at all.
     */
    fun afterTruncation(previous: RequestTuning, caps: ModelCapabilities): RequestTuning =
        previous.copy(maxTokens = clamp(previous.maxTokens * 2, caps))

    /**
     * Keeps a budget inside what the model can actually deliver.
     *
     * The context window has to hold the prompt, the history and the reply. Handing
     * the whole window to the reply leaves nothing for the question, so a fraction of
     * it is the real ceiling — and a weak model gets a smaller fraction, because past
     * a certain length it stops answering and starts looping, and a bigger allowance
     * only buys more of that.
     */
    private fun clamp(requested: Int, caps: ModelCapabilities): Int {
        val share = when (caps.reasoning) {
            ReasoningTier.BASIC -> 0.20
            ReasoningTier.GOOD -> 0.30
            ReasoningTier.STRONG -> 0.40
        }
        val byContext = if (caps.contextTokens > 0) (caps.contextTokens * share).toInt() else CEILING
        return requested.coerceIn(FLOOR, minOf(CEILING, maxOf(FLOOR, byContext)))
    }
}
