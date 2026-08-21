package com.lain.assistant.agent

/** Whether a turn plausibly needs the phone or the live web, or is just talking. */
enum class TurnIntent { CHAT, ACT }

/**
 * A cheap first guess at whether a message needs tools at all.
 *
 * The agent loop is expensive by construction: it ships the full tool surface,
 * the memory block and the recent window, and then the model deliberates over
 * which of thirty tools to use before deciding the answer is "yeah, morning".
 * Most messages in a real conversation are that second kind, and paying agent
 * prices for them is why the app felt sluggish even when nothing was happening.
 *
 * This is deliberately biased towards [ACT]: a false ACT costs the old behaviour,
 * a false CHAT costs a wrong answer. Anything that smells like an instruction, a
 * device noun, or a question about the present goes down the full path. And the
 * fast path is not a commitment either way — the model is given an explicit
 * escape hatch ([NEEDS_TOOLS]) and the engine re-runs the full loop if it uses
 * it, so a misjudgement here costs one short request rather than a bad answer.
 */
object IntentClassifier {

    /** What the fast path replies with when it turns out it did need the toolbox. */
    const val NEEDS_TOOLS = "NEEDS_TOOLS"

    /** Verbs that ask for something to happen on the device. */
    private val actionVerbs = Regex(
        "\\b(open|launch|start|close|quit|kill|call|ring|dial|text|message|whatsapp|sms|email|send|reply|" +
            "play|pause|skip|resume|tap|click|press|type|swipe|scroll|screenshot|photo|picture|record|" +
            "remind|schedule|set|turn|toggle|enable|disable|mute|unmute|silence|install|uninstall|" +
            "download|upload|save|delete|rename|copy|paste|clipboard|note|write|read|show|find|" +
            "look up|search|google|browse|navigate|book|order|pay|share|forward)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Nouns that only come up when the phone itself is the subject. */
    private val deviceNouns = Regex(
        "\\b(battery|wifi|wi-fi|bluetooth|airplane|volume|brightness|screen|notification|contact|" +
            "app|apps|settings|alarm|reminder|camera|gallery|file|folder|storage|data|hotspot|" +
            "whatsapp|spotify|chrome|instagram|youtube|gmail|maps|telegram|signal|messenger)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Anything whose answer depends on today rather than on training. */
    private val liveInfo = Regex(
        "\\b(latest|current|currently|right now|today|tonight|tomorrow|yesterday|this week|this year|" +
            "news|headline|weather|forecast|temperature|price|cost|stock|score|result|won|winner|" +
            "release|released|version|update|who is|who's|when is|when's|how much is|available)\\b",
        RegexOption.IGNORE_CASE
    )

    private val url = Regex("https?://|www\\.|\\b\\w+\\.(com|org|net|io|dev|app|co)\\b", RegexOption.IGNORE_CASE)

    /**
     * @param accessibilityReady when false, screen-driving is impossible anyway, so a
     *   message that only implies on-screen work has nothing to gain from the full loop.
     */
    fun classify(message: String, accessibilityReady: Boolean = true): TurnIntent {
        val text = message.trim()
        if (text.isEmpty()) return TurnIntent.CHAT

        if (url.containsMatchIn(text)) return TurnIntent.ACT
        if (liveInfo.containsMatchIn(text)) return TurnIntent.ACT
        if (deviceNouns.containsMatchIn(text)) return TurnIntent.ACT

        val hasVerb = actionVerbs.containsMatchIn(text)
        if (hasVerb) {
            // "read" and "show" appear in plenty of ordinary sentences ("I read that
            // somewhere"), so a bare verb in a long musing isn't an instruction. A short
            // sentence built around one almost always is.
            val imperative = text.split(Regex("\\s+")).size <= 14
            if (imperative || accessibilityReady) return TurnIntent.ACT
        }

        return TurnIntent.CHAT
    }
}
