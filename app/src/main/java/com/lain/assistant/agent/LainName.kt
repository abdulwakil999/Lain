package com.lain.assistant.agent

/**
 * Getting Lain's own name right, in both directions.
 *
 * Speech recognition has no idea the name exists. "Hello Lain" comes back as
 * "hello lane" — sometimes "line", "laine", "lang" — and that transcript is what
 * reaches the model. It then sees a request addressed to a road, and either
 * answers about one or spends a round trip working out that it shouldn't. The
 * The mic button already worked, so listening started fine and the *message* was
 * wrong, which is the harder version to notice.
 *
 * Text-to-speech is the other direction and had the opposite problem. The name is
 * said "Lane", which is also what an English voice does with the letters L-A-I-N
 * unaided — so the respelling here agrees with the unaided reading rather than
 * fighting it. That is deliberate: a respelling that disagrees is only as good as
 * its own coverage, and every path it misses says something different. This one is
 * belt and braces, not the only thing holding the pronunciation up.
 *
 * Both directions are deterministic string work. Neither costs a model call.
 */
object LainName {

    const val CANONICAL = "Lain"

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
        "thanks", "thank you", "please", "sorry", "morning", "goodnight", "night",
        // The other half of how people actually open: agreeing, refusing, or calling
        // out. All of these were reaching a model as a sentence addressed to a road.
        "yes", "no", "nah", "yeah", "yep", "wait", "listen", "look", "alright",
        "right", "cheers", "afternoon", "evening", "welcome", "bye", "goodbye",
        // How people actually greet her out loud. Every way of putting a word in
        // front of her name has to reach the same place.
        "sup", "wassup", "whatsup", "hiya", "howdy", "hola", "oye", "salam", "yow"
    )

    /**
     * Words that only ever follow a name being addressed.
     *
     * "is" used to be on this list and had to come off. "rain" is a homophone, so
     * "rain is heavy today" matched the rule at position zero and was rewritten into
     * "Lain is heavy today" — the exact failure this whole file exists to avoid,
     * caused by the fix for it. A copula after a noun is ordinary English; a command
     * or a question after a name is not.
     */
    private val VOCATIVE_AFTER = setOf(
        "can", "could", "would", "will", "should", "please", "what", "whats", "why",
        "how", "when", "where", "who", "which", "are", "do", "does", "did",
        "open", "call", "text", "send", "play", "set", "turn", "stop", "tell",
        "show", "read", "find", "close", "search", "remind", "message", "ring",
        "listen", "wake", "help", "give", "make", "put", "take", "let", "get",
        "add", "start", "cancel", "repeat", "answer", "speak", "say",
        // Addressed and then talked about: "Lain I need you", "Lain we're leaving".
        "i", "im", "ive", "we", "you", "my"
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

            // A comma against the word, at the very start, is somebody being spoken to:
            // "Lane, the wifi's off". Restricted to the opening word on purpose — a
            // comma mid-sentence is as likely to be a list ("the aisle, the lane, the
            // window") and rewriting the user's own words there is the failure this
            // whole file exists to prevent.
            val punctuated = i == 0 && words[i].endsWith(",")

            val addressed = when {
                // The whole message is the name.
                words.size == 1 -> true

                punctuated -> true

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

    /** How the name is spelled for a synthesiser so it comes out right. */
    private const val SPOKEN = "Lane"

    /**
     * The name as the assistant's name, capitalised, and not part of a longer word.
     *
     * The capital is load-bearing and is the whole safety mechanism. "Lain" the
     * assistant is always written with one; "lain" the past participle of *lie*
     * ("he had lain there for hours") never is. Matching case-sensitively means
     * ordinary English in a quoted message, a search result or a file the user
     * attached passes through untouched, which is the one thing a global replace
     * would get wrong. Shouting — "LAIN!" — is the one other spelling that is always
     * her, so it is matched too; lowercase deliberately is not.
     *
     * The negative lookbehind covers the remaining case: a sentence that opens with
     * the word, capitalised only because it opens the sentence, after an auxiliary
     * verb. Rare, but "Had Lain there" is not her.
     */
    private val ASSISTANT_NAME =
        Regex("(?<!\\b(?:have|has|had|having)\\s)\\b(?:$CANONICAL|LAIN)\\b")

    /**
     * What the synthesiser should be handed.
     *
     * The name is said "Lane", to rhyme with rain. Handing a synthesiser "Lane"
     * rather than trusting it to work that out from L-A-I-N is what makes it the
     * same every time: neural voices guess at unfamiliar spellings, and guessing is
     * exactly what produced a name that changed between one reply and the next.
     *
     * Because the respelling agrees with what an unaided English voice already does,
     * a path that somehow skips this still comes out right — the difference between
     * a fix and a fix that cannot regress.
     *
     * The UI is untouched: every screen, every message bubble and the app's own
     * label still say "Lain". Only the string on its way into the synthesiser is
     * respelled, and only where the word is her name. Applied centrally, in the one
     * place every engine already calls, so a new voice cannot forget it. Possessives
     * come along for free — "Lain's" becomes "Lane's".
     */
    fun forSpeech(text: String): String {
        if (text.isEmpty()) return text
        if (!text.contains(CANONICAL) && !text.contains("LAIN")) return text
        return ASSISTANT_NAME.replace(text) { if (it.value == "LAIN") SPOKEN.uppercase() else SPOKEN }
    }

    /** Whether a transcript is Lain being addressed by name at all. */
    fun isAddressed(transcript: String): Boolean {
        val normalised = normaliseHeard(transcript)
        return Regex("\\b$CANONICAL\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalised)
    }

    /**
     * Greetings that can sit in front of the name without being part of the command.
     *
     * Deliberately the same shape as [VOCATIVE_BEFORE] but not that set: "sorry" and
     * "thanks" are evidence she is being addressed, and are also things somebody
     * might genuinely be asking her to say.
     */
    private val WAKE_OPENERS = setOf(
        "hello", "hey", "hi", "hiya", "yo", "ok", "okay", "hallo", "helo", "oi",
        "sup", "wassup", "excuse me", "morning", "good morning", "evening",
        "good evening", "afternoon", "good afternoon", "listen", "wait"
    )

    /**
     * The instruction inside a wake phrase, when there is one.
     *
     * "Lain" and "hey Lain" are somebody getting her attention and nothing more.
     * "Lain, open WhatsApp" is one sentence, and answering it with "yes?" makes her
     * feel deaf — the person already said the thing. This pulls off the greeting and
     * the name and hands back whatever is left.
     *
     * @return the command, or null when the phrase was only an address.
     */
    fun commandAfterName(transcript: String): String? {
        val normalised = normaliseHeard(transcript).trim()
        val nameAt = Regex("\\b$CANONICAL\\b", RegexOption.IGNORE_CASE).find(normalised) ?: return null

        // Only trailing text counts. A name at the end ("open whatsapp, Lain") is the
        // command already, and is handled by the branch below.
        val before = normalised.take(nameAt.range.first).trim().trim(',', '.', '!')
        val after = normalised.drop(nameAt.range.last + 1).trim().trimStart(',', '.', '!', '?').trim()

        val leading = before.lowercase().trim()
        val openerOnly = leading.isEmpty() || leading in WAKE_OPENERS

        val candidate = when {
            // "hey Lain open whatsapp" — everything after the name.
            openerOnly && after.isNotEmpty() -> after
            // "open whatsapp Lain" — everything before it, with the address removed.
            after.isEmpty() && before.isNotEmpty() && leading !in WAKE_OPENERS -> before
            else -> return null
        }

        // Two words is the floor. A single stray token after the name is far more
        // often a mis-hearing than an instruction, and acting on one is worse than
        // asking.
        return candidate.takeIf { it.split(Regex("\\s+")).size >= 2 }
    }
}
