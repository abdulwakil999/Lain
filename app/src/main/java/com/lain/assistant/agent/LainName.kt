package com.lain.assistant.agent

/**
 * Getting Lain's own name right, in both directions.
 *
 * Speech recognition has no idea the name exists. "Hello Lain" comes back as
 * "hello lane" — sometimes "line", "laine", "lang" — and that transcript is what
 * reaches the model. It then sees a request addressed to a road, and either
 * answers about one or spends a round trip working out that it shouldn't. The
 * wake-word matcher already tolerated the variants, so listening started fine and
 * the *message* was wrong, which is the harder version to notice.
 *
 * Text-to-speech has the mirror problem: handed "Lain" it says "lane", because
 * that is what the letters look like in English. No Android TTS engine exposes
 * phoneme input reliably, so the only lever is spelling — and the spelling that
 * sounds right is not the one to display.
 *
 * Both fixes are deterministic string work. Neither costs a model call.
 */
object LainName {

    const val CANONICAL = "Lain"

    /**
     * How the name is spelled *for the synthesiser only*.
     *
     * "Lah-een" reads as two syllables to every English TTS voice, which is the
     * pronunciation the name actually has. Never shown on screen — the transcript
     * keeps the real spelling, and only the audio string is rewritten.
     */
    private const val PHONETIC = "Lah-een"

    /**
     * What recognisers actually return for the name.
     *
     * Kept tight. Every entry here is a word that gets rewritten in the user's own
     * message, so a loose list would silently corrupt real sentences — "line" is on
     * it, and "draw a line" must survive, which is what the positional rules below
     * are for.
     */
    private val HOMOPHONES = setOf(
        "lane", "laine", "layne", "lain", "line", "lyn", "lynn", "lang",
        "len", "lean", "lien", "rain", "reign", "rein"
    )

    /**
     * Words that mean the next token is being *addressed*, not described.
     *
     * "hello lane" is the name; "the fast lane" is a road. Position carries the
     * distinction reliably and cheaply, where a bare word swap would not.
     */
    private val VOCATIVE_BEFORE = setOf(
        "hello", "hey", "hi", "yo", "ok", "okay", "hallo", "helo", "oi", "excuse me",
        "thanks", "thank you", "please", "sorry", "morning", "goodnight", "night"
    )

    /** Words that only ever follow a name being addressed. */
    private val VOCATIVE_AFTER = setOf(
        "can", "could", "would", "will", "please", "what", "whats", "why", "how", "when",
        "where", "who", "are", "is", "do", "does", "did", "open", "call", "text",
        "send", "play", "set", "turn", "stop", "tell", "show", "read", "find"
    )

    /**
     * Verbs that open an instruction to an assistant.
     *
     * Used only to qualify a trailing name. "Open WhatsApp, Lain" is an address;
     * "draw a straight line" is not, and both end in a homophone.
     */
    private val COMMAND_VERBS = setOf(
        "open", "call", "text", "send", "play", "set", "turn", "stop", "tell",
        "show", "read", "find", "close", "search", "remind", "message", "ring"
    )

    /**
     * Words that mark the next token as a thing rather than a person.
     *
     * "the line", "a lane", "about the line" — an article or preposition in front
     * is the clearest signal available that a name is not being used.
     */
    private val DETERMINERS = setOf(
        "the", "a", "an", "this", "that", "my", "your", "his", "her", "their",
        "of", "in", "on", "at", "to", "about", "from", "into", "with", "another",
        "straight", "same", "next", "fast", "slow", "left", "right", "first", "last"
    )

    /**
     * Rewrites a recognised transcript so the name is the name.
     *
     * Only touches a homophone that is genuinely being used to address her — after a
     * greeting, before an instruction, or standing alone as the whole utterance.
     * "Book me the window seat, not the aisle or the lane" comes through untouched.
     */
    fun normaliseHeard(transcript: String): String {
        if (transcript.isBlank()) return transcript

        // Split into words while keeping the separators, so spacing and punctuation
        // survive a rewrite untouched.
        val tokens = Regex("(\\s+)").split(transcript)
        if (tokens.isEmpty()) return transcript

        val words = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return transcript

        val rewritten = words.toMutableList()
        var changed = false

        for (i in words.indices) {
            val bare = words[i].trim { !it.isLetter() }.lowercase()
            if (bare !in HOMOPHONES) continue
            // Already correct, and correctly cased.
            if (words[i].trim { !it.isLetter() } == CANONICAL) continue

            val before = words.getOrNull(i - 1)?.let(::key)
            val after = words.getOrNull(i + 1)?.let(::key)
            val opener = words.firstOrNull()?.let(::key)

            val addressed = when {
                // The whole message is the name.
                words.size == 1 -> true

                // An article or preposition in front means it is a thing, not a
                // person — "the line", "about the line" — and that beats everything
                // else, because this rewrites the user's own words.
                before in DETERMINERS -> false

                before in VOCATIVE_BEFORE -> true
                after in VOCATIVE_AFTER -> true

                // Trailing address: "open whatsapp, Lain". Requires the sentence to
                // have opened with an instruction, or "draw a straight line" gets
                // rewritten into an address to somebody.
                i == words.lastIndex && words.size >= 3 && opener in COMMAND_VERBS -> true

                else -> false
            }
            if (!addressed) continue

            // Preserve whatever punctuation was attached: "lane," stays "Lain,".
            rewritten[i] = words[i].replace(
                Regex(Regex.escape(words[i].trim { !it.isLetter() }), RegexOption.IGNORE_CASE),
                CANONICAL
            )
            changed = true
        }

        return if (changed) rewritten.joinToString(" ") else transcript
    }

    /** A word reduced to what it is, for comparison: letters only, lowercase. */
    private fun key(word: String): String =
        word.filter { it.isLetter() }.lowercase()

    /**
     * Rewrites text for the synthesiser so the name is pronounced, not read.
     *
     * Word-boundary matched and case-preserving in intent: only the standalone name
     * is respelled, so "Lain's" becomes "Lah-een's" and a word merely containing the
     * letters is left alone.
     */
    fun forSpeech(text: String): String =
        if (text.isBlank()) text
        else Regex("\\bLain\\b", RegexOption.IGNORE_CASE).replace(text, PHONETIC)

    /** Whether a transcript is Lain being addressed by name at all. */
    fun isAddressed(transcript: String): Boolean {
        val normalised = normaliseHeard(transcript)
        return Regex("\\b$CANONICAL\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalised)
    }
}
