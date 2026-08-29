package com.lain.assistant.agent

/**
 * Carries the language breakdown of a local reply through to the synthesiser.
 *
 * The reply itself travels to the UI as a plain string, which is right — the
 * transcript should hold what was said, not how to pronounce it. But the speaker
 * needs to know that "Vale." in "Vale. Anything else?" is Spanish, and a
 * whole-string language guess will always call that line English and read the
 * Spanish word as two English syllables.
 *
 * A one-slot handover rather than a parameter threaded through six layers: local
 * replies are produced and spoken within the same turn, one at a time, so the
 * simplest correct thing is to leave the breakdown where the speaker can find it
 * and expire it as soon as it is claimed.
 */
object SpokenSegments {

    @Volatile
    private var pending: Replies.Spoken? = null

    fun remember(spoken: Replies.Spoken) {
        pending = spoken
    }

    /**
     * The breakdown for [text], if this is the line that was just produced locally.
     *
     * Matched on the text and cleared on read, so a stale breakdown can never be
     * applied to a later reply from the model.
     */
    fun claim(text: String): List<Replies.Spoken.Segment>? {
        val held = pending ?: return null
        pending = null
        return if (held.text == text) held.segments else null
    }
}
