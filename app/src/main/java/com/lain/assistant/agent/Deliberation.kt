package com.lain.assistant.agent

/**
 * Recognises a reply that is the model thinking out loud instead of doing the job.
 *
 * Weak models fail this way constantly on an action request: asked to text someone,
 * they answer "Okay, so the user wants me to send a message. I should first look up
 * the contact, then..." and stop. No tool call, no action, and the user is shown a
 * paragraph of planning as though it were an answer. It reads like Lain talking to
 * herself, because it is.
 *
 * This is not the marker-based stripping [com.lain.assistant.network.ReasoningFilter]
 * does — there are no tags here, just prose. It cannot be edited out safely, so it is
 * detected and the model is pushed once to actually act. If it deliberates again, the
 * text is shown as an honest failure rather than dressed up as a result.
 *
 * Deliberately conservative. A false positive costs one extra round trip; being too
 * eager would suppress genuine answers that happen to explain themselves.
 */
object Deliberation {

    /**
     * Headers that announce reasoning outright.
     *
     * Unambiguous by construction: nothing that opens "Here's a thinking process:" is
     * an answer. This is the family that reached the user verbatim — a numbered plan
     * about how to say a two-word name — and it did so on the conversational path,
     * which the deliberation check below had been skipping on the reasoning that
     * prose *is* the answer in plain chat. It usually is. Not after one of these.
     */
    private val PREAMBLE_HEADERS = listOf(
        "here's a thinking process", "here is a thinking process",
        "here's my thinking", "here is my thinking", "here's my thought process",
        "thinking process:", "thought process:", "my reasoning:", "reasoning:",
        "analysis:", "let me think through", "let's think through",
        "step-by-step reasoning", "chain of thought", "internal monologue",
        "here's how i'll approach", "here is how i will approach",
        "let me work through this", "breaking this down:"
    )

    /** An answer and the working that came with it. */
    data class Split(val answer: String?, val working: String?)

    /**
     * Separates a reply that led with its reasoning.
     *
     * Models that do this almost always finish with the actual answer, so throwing
     * the whole reply away would lose it. The last short, plain paragraph is the
     * answer; everything above it is working. When no such paragraph exists — the
     * model narrated and stopped, or was cut off — the answer is null and the caller
     * pushes it to try again rather than showing a plan as though it were a result.
     */
    fun split(text: String): Split {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Split(null, null)

        val opening = trimmed.take(80).lowercase()
        val leadsWithReasoning = PREAMBLE_HEADERS.any { opening.contains(it) }
        if (!leadsWithReasoning) return Split(trimmed, null)

        // Paragraphs, last first: the answer is at the bottom if it is anywhere.
        val paragraphs = trimmed.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val answer = paragraphs.asReversed().firstOrNull { candidate ->
            candidate.length in 1..400 &&
                // Still part of the plan: numbered steps, bullets, or a labelled section.
                !candidate.matches(Regex("(?s)^\\s*(\\d+[.)]|[-*•]|#+)\\s.*")) &&
                !PREAMBLE_HEADERS.any { candidate.lowercase().startsWith(it) } &&
                // A sentence about what it is going to do is not what it did.
                !Regex("\\b(i'll|i will|i should|let me|i need to)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(candidate)
        }

        return if (answer == null) {
            Split(null, trimmed)
        } else {
            Split(answer, trimmed.removeSuffix(answer).trim().ifBlank { null })
        }
    }

    /** Openers that only ever introduce planning, never an answer. */
    private val OPENERS = listOf(
        "okay, so the user", "ok, so the user", "so the user wants",
        "the user wants me to", "the user is asking me to", "the user is asking for",
        "let me think", "let's think", "i need to figure out", "first, i need to",
        "first i need to", "i should start by", "my plan is to", "here's my plan",
        "to do this, i", "to do this i", "step 1:", "step 1.", "thinking:",
        "alright, so", "let me break", "i'll need to call", "i should call the"
    )

    /** Phrases that describe an intention rather than report a result. */
    private val INTENTIONS = listOf(
        "i will now", "i'm going to", "i am going to", "i should", "i need to",
        "next, i", "then i will", "let me", "i'll use", "i will use", "i can use",
        "i'll call the", "i will call the"
    )

    /** Words that mean something actually happened. Their presence argues against. */
    private val COMPLETED = listOf(
        "sent", "called", "opened", "set for", "done", "already", "couldn't",
        "can't", "failed", "is now", "here's", "here is", "it's", "you have"
    )

    /**
     * @param text the model's prose reply
     * @param actedThisTurn whether any tool ran during the turn — if something was
     *        actually done, a reflective sentence about it is a summary, not a stall
     */
    fun isThinkingOutLoud(text: String, actedThisTurn: Boolean): Boolean {
        if (actedThisTurn) return false
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false

        // Short replies are answers, not monologue. "62%." and "Opened Spotify." are
        // the shape of a working assistant.
        if (t.length < MIN_LENGTH) return false

        // A question back to the user is a legitimate reply — asking which Moyo, or
        // for a missing detail, is exactly what should happen.
        if (t.endsWith("?")) return false

        // A declared thinking preamble is decisive on its own.
        if (PREAMBLE_HEADERS.any { t.take(80).contains(it) }) return true

        val opens = OPENERS.any { t.startsWith(it) }
        val intends = INTENTIONS.count { t.contains(it) }
        val reports = COMPLETED.count { t.contains(it) }

        // An opener is strong evidence on its own; otherwise it takes repeated
        // statements of intent with nothing reported as done.
        return when {
            opens && reports == 0 -> true
            intends >= 2 && reports == 0 -> true
            else -> false
        }
    }

    private const val MIN_LENGTH = 60

    /**
     * The nudge sent back when this is caught.
     *
     * Short and specific on purpose. A long correction is another wall of text for a
     * weak model to pattern-match against, and the failure is not one of knowledge —
     * it already knows what to do, it just narrated instead of doing it.
     */
    const val NUDGE =
        "That was planning, not action, and the user cannot see it. Call the tool now, " +
            "or say plainly what you need from them. Do not describe what you intend to do."
}
