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
