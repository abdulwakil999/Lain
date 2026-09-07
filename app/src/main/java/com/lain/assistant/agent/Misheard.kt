package com.lain.assistant.agent

/**
 * Repairs the words a speech recogniser has no way to know.
 *
 * Android's recogniser has a general English vocabulary. It has never heard of
 * Claude, DeepSeek, Qwen or OpenRouter, so it returns the nearest thing it does
 * know: "cloud", "deep seek", "quen", "open router". That transcript is what
 * reaches the router and the model, and "switch to cloud" is a request about
 * weather or storage — so she either answers the wrong question or spends a round
 * trip discovering there wasn't one. It is the same failure [LainName] fixes for
 * her own name, one vocabulary wider.
 *
 * The danger is the obvious one, and it is why this file is shaped the way it is: a
 * blind swap of "cloud" corrupts every real sentence containing the word. So the
 * corrections come in two kinds.
 *
 * **Spellings that are not English.** "claud", "deep seek", "chat gbt" — nobody
 * says these meaning anything else, so they are rewritten wherever they appear.
 *
 * **Ordinary words that are also misheard names.** "cloud", "lama", "mistrial".
 * These are rewritten only when the sentence is plainly about models, and never
 * when it is plainly about something else. Three gates have to agree, and the
 * default when they don't is to leave the user's words exactly as they said them.
 *
 * Deterministic string work, no model call, a few microseconds.
 */
object Misheard {

    /**
     * Misheard spellings that are not words in their own right.
     *
     * Safe to rewrite anywhere, because there is no sentence where somebody meant
     * the literal string "chat gbt". Longest first, so "chat gbt" is matched before
     * anything tries to do something with "chat".
     */
    private val UNAMBIGUOUS: List<Pair<String, String>> = listOf(
        // Anthropic
        "claud" to "Claude",
        "clawd" to "Claude",
        "klaud" to "Claude",
        "cloude" to "Claude",
        "claude's" to "Claude's",
        "anthropik" to "Anthropic",
        "anthropics" to "Anthropic",
        // OpenAI
        "chat gbt" to "ChatGPT",
        "chat gpt" to "ChatGPT",
        "chatgbt" to "ChatGPT",
        "chat jipity" to "ChatGPT",
        "open ai" to "OpenAI",
        // Google
        "gemeni" to "Gemini",
        "jiminy" to "Gemini",
        "gemini's" to "Gemini's",
        // DeepSeek
        "deep seek" to "DeepSeek",
        "deepseak" to "DeepSeek",
        "deep sick" to "DeepSeek",
        // Others in the catalogue
        "quen" to "Qwen",
        "kwen" to "Qwen",
        "grock" to "Grok",
        // Apps she is asked to open by name
        "whats app" to "WhatsApp",
        "what's app" to "WhatsApp",
        "whatts app" to "WhatsApp",
        "spotifi" to "Spotify",
        "spotify's" to "Spotify's",
        "you tube" to "YouTube",
        "shazaam" to "Shazam",
        "shazzam" to "Shazam",
        // Recitation
        "koran" to "Qur'an",
        "quran" to "Qur'an",
        "kuran" to "Qur'an"
    ).sortedByDescending { it.first.length }

    /**
     * Real English words that are also what a model's name comes back as.
     *
     * Every one of these has an ordinary meaning that has to survive: "the cloud is
     * grey", "the lama spat at me", "the judge declared a mistrial". None of them is
     * rewritten unless the sentence is about models, and none of them is rewritten
     * if the sentence looks like it is about the ordinary thing.
     */
    private val IN_MODEL_CONTEXT: List<Pair<String, String>> = listOf(
        "cloud" to "Claude",
        "clawed" to "Claude",
        "clod" to "Claude",
        "cloud's" to "Claude's",
        "lama" to "Llama",
        "mistrial" to "Mistral",
        "open router" to "OpenRouter",
        "open rooter" to "OpenRouter"
    ).sortedByDescending { it.first.length }

    /**
     * Evidence the sentence is about which model is answering.
     *
     * One of these has to be present before an ordinary word is touched. They are
     * the words that actually surround a model switch — "use", "switch to", "which
     * model", "api key" — plus the names of the other models, because people compare
     * them out loud.
     */
    private val MODEL_CONTEXT = setOf(
        "model", "models", "ai", "llm", "llms", "brain", "engine",
        "anthropic", "openai", "openrouter", "provider", "api", "key",
        "switch", "switched", "switching", "use", "using", "run", "running",
        "ask", "asking", "talk", "talking", "answer", "answering", "reply",
        "sonnet", "opus", "haiku", "gpt", "chatgpt", "gemini", "deepseek",
        "llama", "mistral", "qwen", "grok", "token", "tokens", "prompt",
        "free", "paid", "smarter", "dumber", "better", "faster"
    )

    /**
     * Evidence the sentence is about the ordinary thing.
     *
     * A single one of these anywhere in the utterance calls the whole thing off,
     * even if a context word is also present — "upload it to the cloud using my
     * account" contains "using", and is unmistakably not about Anthropic. Refusing
     * on any doubt is the right bias here: a missed correction costs one round trip,
     * a wrong one rewrites what somebody said.
     */
    private val NOT_A_MODEL = setOf(
        "sky", "skies", "weather", "rain", "raining", "rainy", "sun", "sunny",
        "storm", "thunder", "forecast", "cloudy", "clouds",
        "storage", "drive", "upload", "uploaded", "uploading", "download",
        "backup", "backed", "sync", "server", "servers", "hosting", "host",
        "aws", "azure", "icloud", "dropbox", "photos", "gallery", "files",
        // The animal and the courtroom, for the other two.
        "zoo", "farm", "spat", "wool", "andes", "peru",
        "judge", "jury", "court", "trial", "lawyer", "verdict"
    )

    /**
     * Words that mark the next one as a thing rather than a name.
     *
     * "the cloud" is a place you put files; "cloud" after a verb is what somebody
     * calls the model. Prepositions are deliberately not on this list — "switch to
     * cloud" is the single most common way the sentence gets said.
     */
    private val DETERMINERS = setOf(
        "the", "a", "an", "my", "your", "our", "their", "his", "her",
        "this", "that", "these", "those", "some", "any", "no"
    )

    /**
     * Puts back the words the recogniser could not have known.
     *
     * @param transcript what the recogniser returned.
     * @return the same string with known vocabulary restored, or the original
     *   untouched when nothing applied — which is the common case and the safe one.
     */
    fun correct(transcript: String): String {
        if (transcript.isBlank()) return transcript

        var out = transcript
        for ((heard, actual) in UNAMBIGUOUS) {
            out = replaceWords(out, heard, actual)
        }

        if (looksLikeModelTalk(out)) {
            for ((heard, actual) in IN_MODEL_CONTEXT) {
                out = replaceWords(out, heard, actual, guardDeterminer = true)
            }
        }
        return out
    }

    /**
     * Whether the sentence is about which model is answering.
     *
     * Both halves matter: something has to point at models, and nothing may point
     * away from them.
     */
    private fun looksLikeModelTalk(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
        if (words.none { it in MODEL_CONTEXT }) return false
        return words.none { it in NOT_A_MODEL }
    }

    /**
     * Replaces whole words or whole phrases, never fragments.
     *
     * Word boundaries are the entire safety of the unambiguous list: without them
     * "lama" rewrites the middle of "Salamanca", and "clod" the middle of nothing
     * anybody wants either.
     */
    private fun replaceWords(
        text: String,
        heard: String,
        actual: String,
        guardDeterminer: Boolean = false
    ): String {
        val pattern = Regex(
            "(?<![\\p{L}'])" + Regex.escape(heard).replace("\\ ", "\\s+") + "(?![\\p{L}'])",
            RegexOption.IGNORE_CASE
        )
        if (!pattern.containsMatchIn(text)) return text

        return pattern.replace(text) { match ->
            val before = text.take(match.range.first)
                .split(Regex("[^\\p{L}']+")).lastOrNull { it.isNotEmpty() }?.lowercase()
            if (guardDeterminer && before in DETERMINERS) match.value else actual
        }
    }
}
