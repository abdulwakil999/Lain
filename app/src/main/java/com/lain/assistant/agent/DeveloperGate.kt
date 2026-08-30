package com.lain.assistant.agent

/**
 * The one question that separates her developer from anyone who says they are.
 *
 * Claiming to be the developer costs nothing — it is six words in a text box — so
 * the claim on its own is worth nothing, and treating it as proof would hand
 * anybody who tried it whatever deference the claim buys. The challenge is a shared
 * secret: a question with one answer, known to one person, not derivable from
 * anything the app ships or the model knows.
 *
 * Deliberately not a security boundary, and nothing in the app grants power on the
 * strength of it. It changes how she talks to someone, nothing more. Anything that
 * actually matters — sending, calling, deleting — stays behind the same
 * confirmation for the developer as for everyone else, because a secret typed into
 * a chat box is not an authentication system and pretending otherwise would be the
 * more dangerous lie.
 *
 * Held in memory rather than on disk: a challenge is a live exchange, and one left
 * armed across a restart would meet an unrelated message weeks later and call the
 * user a fraud for it.
 */
object DeveloperGate {

    /** Only he knows this. Compared case-insensitively; nothing else about it is loose. */
    private const val ANSWER = "soft"

    @Volatile
    private var awaitingAnswer = false

    /** True while the next message will be read as an answer to the challenge. */
    val isArmed: Boolean get() = awaitingAnswer

    fun arm() {
        awaitingAnswer = true
    }

    fun disarm() {
        awaitingAnswer = false
    }

    /**
     * Reads the reply to the challenge and closes it either way.
     *
     * Accepts the answer as a word inside a sentence, not only as the whole message:
     * "soft", "Soft.", "she's soft" and "the answer is soft" are the same person
     * answering the same question, and failing the real developer on punctuation
     * would be a worse outcome than letting a guess through — there is nothing to
     * guess, since the word means nothing to anyone who does not already know.
     *
     * @return true when the answer was right.
     */
    fun answer(text: String): Boolean {
        awaitingAnswer = false
        return Regex("\\b$ANSWER\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
