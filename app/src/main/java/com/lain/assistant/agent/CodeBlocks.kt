package com.lain.assistant.agent

/**
 * Splits a reply into prose and fenced code.
 *
 * One parser, two consumers, and they have opposite needs. The transcript wants the
 * code kept exactly — a snippet with its indentation collapsed into the surrounding
 * paragraph is not code any more, it is a description of code. The speaker wants it
 * gone: forty lines of Kotlin read aloud, brace by brace, is the single worst thing
 * a talking assistant can do, and it takes a minute and a half.
 *
 * Deliberately a small hand-written scanner rather than a Markdown library. The only
 * construct that has to be exactly right is the triple-backtick fence; everything
 * else in the reply is prose and stays prose. A dependency that also parsed tables
 * and footnotes would be a megabyte to get the same three lines right.
 */
object CodeBlocks {

    /**
     * @param language the fence's info string ("python", "kotlin"), or null when the
     *   model didn't say. Never guessed at — a wrong label on a copied snippet is
     *   worse than no label.
     */
    data class Block(val text: String, val isCode: Boolean, val language: String? = null)

    private const val FENCE = "```"

    /**
     * The reply in order, prose and code alternating.
     *
     * A fence that is opened and never closed still yields its code — the model was
     * cut off mid-snippet, and showing what arrived beats showing three backticks
     * and a wall of unstyled text.
     */
    fun split(text: String): List<Block> {
        if (!text.contains(FENCE)) return listOf(Block(text, isCode = false))

        val blocks = mutableListOf<Block>()
        var cursor = 0
        while (true) {
            val open = text.indexOf(FENCE, cursor)
            if (open < 0) break

            text.substring(cursor, open).takeIf { it.isNotBlank() }
                ?.let { blocks += Block(it.trim('\n'), isCode = false) }

            // The rest of the opening line is the language, when there is one.
            val afterFence = open + FENCE.length
            val lineEnd = text.indexOf('\n', afterFence).takeIf { it >= 0 } ?: text.length
            val language = text.substring(afterFence, lineEnd).trim().takeIf { it.isNotEmpty() }

            val bodyStart = (lineEnd + 1).coerceAtMost(text.length)
            val close = text.indexOf(FENCE, bodyStart)
            val body = if (close < 0) text.substring(bodyStart) else text.substring(bodyStart, close)

            blocks += Block(body.trimEnd('\n'), isCode = true, language = language)
            if (close < 0) return blocks
            cursor = close + FENCE.length
        }

        text.substring(cursor).takeIf { it.isNotBlank() }
            ?.let { blocks += Block(it.trim('\n'), isCode = false) }
        return blocks
    }

    /** True when the reply carries at least one non-empty fenced block. */
    fun containsCode(text: String): Boolean =
        text.contains(FENCE) && split(text).any { it.isCode && it.text.isNotBlank() }

    /**
     * The reply with code replaced by a spoken placeholder.
     *
     * Says that the code is there rather than dropping it silently, because "here's
     * the function" followed by nothing sounds like the answer failed. Naming the
     * language when the model gave one is the one useful thing that survives audio.
     */
    fun forSpeech(text: String): String {
        if (!containsCode(text)) return text
        val spoken = split(text).mapNotNull { block ->
            when {
                !block.isCode -> block.text.takeIf { it.isNotBlank() }
                else -> {
                    val lines = block.text.lines().count { it.isNotBlank() }
                    val what = block.language?.let { "$it code" } ?: "code"
                    "($what on screen, $lines ${if (lines == 1) "line" else "lines"})"
                }
            }
        }
        return spoken.joinToString(" ").trim()
    }
}
