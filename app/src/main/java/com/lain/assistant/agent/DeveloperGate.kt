package com.lain.assistant.agent

/**
 * The one question that separates the role's holder from anyone who says they hold it.
 *
 * Claiming the role costs nothing — it is six words in a text box — so the claim on
 * its own is worth nothing, and treating it as proof would hand anybody who tried it
 * whatever deference the claim buys. The check is a shared secret: a question with
 * one answer, known to one person, not derivable from anything the app ships or the
 * model knows. Neither the question's subject nor its answer is written in this file;
 * both live in [Vault], and the answer only as a digest.
 *
 * Deliberately not a security boundary, and nothing in the app grants power on the
 * strength of it. It changes how she talks to someone, nothing more. Anything that
 * actually matters — sending, calling, deleting — stays behind the same confirmation
 * for the role's holder as for everyone else, because a phrase typed into a chat box
 * is not an authentication system and pretending otherwise would be the more
 * dangerous lie.
 *
 * There are two gates here and they are separate on purpose. One admits; the other
 * stands the role down on a device that should no longer carry it. Both take two
 * steps, so neither happens by a single unlucky sentence.
 */
object DeveloperGate {

    @Volatile
    private var awaitingAnswer = false

    @Volatile
    private var awaitingStandDown = false

    /** True while the next message will be read as an answer to the challenge. */
    val isArmed: Boolean get() = awaitingAnswer

    /** True while the next message will be read as confirming a stand-down. */
    val isStandingDown: Boolean get() = awaitingStandDown

    fun arm() {
        awaitingAnswer = true
        awaitingStandDown = false
    }

    fun disarm() {
        awaitingAnswer = false
        awaitingStandDown = false
    }

    /**
     * Reads the reply to the challenge and closes it either way.
     *
     * Accepts the answer as a word inside a sentence, not only as the whole message:
     * a person answering a question does not necessarily answer in one word, and
     * failing the real holder on punctuation would be a worse outcome than letting a
     * guess through — there is nothing to guess, since the word means nothing to
     * anyone who does not already know it.
     *
     * @return true when the answer was right.
     */
    fun answer(text: String): Boolean {
        awaitingAnswer = false
        return Vault.isPass(text)
    }

    /**
     * Whether this message is the phrase that stands the role down.
     *
     * Said once to ask, and once more to mean it. Two steps because the alternative
     * is a device quietly losing the role over a sentence that happened to contain
     * the phrase, and the only way back is knowing the answer to the challenge again
     * — which, on a device that is not his, is no way back at all.
     */
    fun looksLikeStandDown(text: String): Boolean = Vault.isStandDown(text)

    /** Arms the confirmation. The next message has to repeat the phrase. */
    fun askToStandDown() {
        awaitingStandDown = true
        awaitingAnswer = false
    }

    /** @return true when the phrase was repeated and the role should be given up. */
    fun confirmStandDown(text: String): Boolean {
        val confirmed = awaitingStandDown && Vault.isStandDown(text)
        awaitingStandDown = false
        return confirmed
    }
}
