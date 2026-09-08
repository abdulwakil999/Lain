package com.lain.assistant.data

/**
 * Everything that goes wrong between a key on a website and a key in a header.
 *
 * "My API key is rejected" is almost never a wrong key. It is a right key with
 * something invisible attached to it, and the reason it is hard to report is that
 * the field hides what it holds behind dots — so the person looking at it cannot
 * see the thing that is breaking it. What actually arrives:
 *
 * - **A capital first letter.** The key field ran the ordinary text keyboard, so
 *   the IME applied sentence capitalisation and `sk-or-v1-…` was stored as
 *   `Sk-or-v1-…`. Fixed at the field, not here, but it is the same family.
 * - **A non-breaking space or a zero-width character**, picked up copying from a
 *   web page or a chat message. `String.trim()` does not remove either, because
 *   neither is whitespace as far as Java is concerned. A header cannot carry them
 *   at all: OkHttp throws rather than sending one, so the failure arrived as a
 *   stack trace about an unexpected char instead of anything a person could act on.
 * - **A newline**, from copying a whole line out of a terminal or a `.env` file.
 * - **"Bearer " in front**, copied from a curl example or an API doc.
 * - **Quotation marks around it**, from JSON or a config file.
 *
 * All of it is removable without guessing, because an API key is printable ASCII
 * with no spaces in it — every provider's is. Anything else in the string was not
 * part of the key, which is what makes stripping it safe rather than destructive:
 * a character that cannot legally go in an HTTP header was never the user's key.
 *
 * [problem] exists so the app can *say* what it removed. Silently fixing a key and
 * silently rejecting one look identical from outside, and this project's rule is
 * that the honest report beats the confident guess.
 */
object ApiKeys {

    /**
     * "Bearer sk-…", "Authorization: Bearer sk-…", "x-api-key: sk-…", "API key = sk-…".
     *
     * All four are what somebody pastes when they copy the example rather than the
     * value. Sending the prefix along produces `Bearer Bearer sk-…`, which is a 401
     * that looks exactly like a wrong key.
     */
    private val AUTH_PREFIX = Regex(
        "^\\s*(?:(?:authorization|x-api-key|api[-_ ]?key|key)\\s*[:=]\\s*)?(?:bearer|token)?\\s*",
        RegexOption.IGNORE_CASE
    )

    /** Wrapping punctuation from JSON, YAML, Markdown or a shell command. */
    private const val WRAPPERS = "\"'`<>[](){}\u201C\u201D\u2018\u2019"

    /**
     * What a key is allowed to be made of.
     *
     * Printable ASCII with no space — the range every HTTP header value may carry,
     * minus the one character no key contains. Everything outside it is something
     * that came along for the ride.
     */
    private fun isKeyChar(c: Char): Boolean = c.code in 0x21..0x7E

    /**
     * A character that occupies no visible space and cannot be typed on purpose.
     *
     * The non-breaking space, the zero-width space and joiners, the byte-order mark,
     * the soft hyphen. Kotlin's trim() removes some of these and not others, which is
     * exactly why they cannot be left to it.
     */
    private fun isInvisible(c: Char): Boolean =
        c.category == CharCategory.FORMAT ||
            c.code == 0xFEFF || c.code == 0x00AD ||
            (c.isWhitespace() && c != ' ' && c != '\t' && c != '\n' && c != '\r')

    /** The key as it should have been stored, with everything that isn't it removed. */
    fun clean(raw: String): String = inspect(raw).cleaned

    /**
     * Whether the stored value is worth sending at all.
     *
     * Blank is not a failure to report — it is somebody who has not filled the field
     * in yet, and the app already has a message for that.
     */
    fun isUsable(raw: String?): Boolean = !raw.isNullOrBlank() && clean(raw).isNotEmpty()

    /**
     * What was wrong with what the user pasted, in a sentence, or null if nothing.
     *
     * Deliberately describes the repair rather than refusing: the key still works
     * after cleaning, and telling somebody their key was rejected when it was
     * actually accepted after a fix would be its own kind of lie.
     */
    fun problem(raw: String): String? {
        if (raw.isBlank()) return null
        val found = inspect(raw)
        if (found.cleaned.isEmpty()) return "There's no key in that — only punctuation or spaces."
        return when {
            found.hadInvisible ->
                "There was an invisible character in that key — a non-breaking or zero-width " +
                    "space, the kind copying from a web page leaves behind. Removed it; that " +
                    "alone is enough to get a key rejected."
            found.hadPrefix ->
                "Dropped the \"Bearer\" in front — the key is only the part after it."
            found.hadWrapper ->
                "Dropped the quotes around it."
            found.hadWhitespace ->
                "Removed a stray space or line break from the key."
            found.hadOther ->
                "Removed a character that can't go in a request header."
            else -> null
        }
    }

    /**
     * A key that belongs to a different provider than the one selected.
     *
     * Reported only when the key carries *another known provider's* prefix, never
     * merely because it doesn't match the expected one — providers change their key
     * formats, and refusing an unfamiliar shape would break the app the day one of
     * them does. Recognising a key as somebody else's is safe; failing to recognise
     * one as your own is not.
     *
     * @return the sentence to show, or null when there is nothing to say.
     */
    fun mismatch(provider: Provider, raw: String): String? {
        val key = clean(raw)
        if (key.isEmpty()) return null
        val looksLike = KNOWN_PREFIXES.firstOrNull { (prefix, _) -> key.startsWith(prefix) }?.second
            ?: return null
        if (looksLike == provider) return null
        return "That looks like a ${looksLike.displayName} key, and the provider is set to " +
            "${provider.displayName}. Either switch the provider, or paste the key from " +
            "${provider.displayName}."
    }

    /**
     * Prefixes distinctive enough to identify, longest first.
     *
     * `sk-` alone is deliberately last: OpenAI uses it, and so does anything that
     * copied OpenAI's format, so it identifies a key only once the more specific
     * prefixes have failed to.
     */
    private val KNOWN_PREFIXES: List<Pair<String, Provider>> = listOf(
        "sk-or-" to Provider.OPENROUTER,
        "sk-ant-" to Provider.ANTHROPIC,
        "xai-" to Provider.GROK,
        "AIza" to Provider.GEMINI,
        "sk-proj-" to Provider.OPENAI,
        "sk-" to Provider.OPENAI
    ).sortedByDescending { it.first.length }

    private data class Found(
        val cleaned: String,
        val hadPrefix: Boolean,
        val hadWrapper: Boolean,
        val hadWhitespace: Boolean,
        val hadInvisible: Boolean,
        val hadOther: Boolean
    )

    /**
     * One pass that both cleans and records what it had to clean.
     *
     * Kept together so [clean] and [problem] cannot ever disagree about what
     * happened — two implementations of the same rules is how a message ends up
     * describing a repair that wasn't made.
     */
    private fun inspect(raw: String): Found {
        // Checked before anything is trimmed. Kotlin's trim() counts a non-breaking
        // space as whitespace where Java's does not, so it silently removes the very
        // character most worth telling somebody about — asking afterwards would
        // always answer no.
        val hadInvisible = raw.any(::isInvisible)

        // The prefix comes off after the edges, because a paste that carries both a
        // stray space and "Bearer" has the space first, and the regex cannot reach
        // past one it does not recognise as whitespace.
        val stripped = raw.trim()
        val prefix = AUTH_PREFIX.find(stripped)?.value.orEmpty()
        // A match of nothing but spaces is not a prefix. Only letters — "bearer",
        // "authorization:" — mean something was actually taken off the front.
        val hadPrefix = prefix.any { it.isLetter() }
        val body = stripped.substring(prefix.length).trim()

        val unwrapped = body.trim { it in WRAPPERS }.trim()
        val hadWrapper = unwrapped != body

        var hadWhitespace = false
        var hadOther = false
        val cleaned = buildString {
            for (c in unwrapped) {
                when {
                    isKeyChar(c) -> append(c)
                    // Inside the key rather than around it, so worth a word: this is
                    // a key that was pasted in two pieces, or with something after it.
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> hadWhitespace = true
                    isInvisible(c) -> Unit
                    else -> hadOther = true
                }
            }
        }

        return Found(cleaned, hadPrefix, hadWrapper, hadWhitespace, hadInvisible, hadOther)
    }
}
