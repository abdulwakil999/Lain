package com.lain.assistant.agent

import java.text.Normalizer
import java.util.Locale

/**
 * Matching spoken names against real ones — app labels, contacts, playlists.
 *
 * Exact-match lookup was wrong in every direction people actually talk:
 *
 *  - **Case.** "MOYO", "Moyo" and "moyo" are one person. Case never carries meaning
 *    in a name and comparing it was pure noise.
 *  - **Extra words.** "open call of duty mobile" names an app called "Call of Duty".
 *    A `contains` test fails here, because the *query* is longer than the label.
 *  - **Missing words.** "call moyo" should reach "MoyOma", and "open duty" should
 *    still find Call of Duty.
 *  - **Accents and punctuation.** "Moyo-Ade", "Móyò" and "Moyo Ade" are the same
 *    person to a speaker and three different strings to a computer.
 *  - **Speech slips.** Recognisers routinely return "watsapp" or "spotifi".
 *
 * So this scores candidates rather than filtering them, and the caller decides what
 * to do with a weak or ambiguous result. Deliberately not a general edit-distance
 * search: on real data that ranks "Mona" and "Moyo" as near-identical, and calling
 * the wrong person is not a recoverable mistake.
 */
object FuzzyMatch {

    /**
     * Confidence in a candidate, 0..1.
     *
     * The bands the callers use:
     *  - `>= CERTAIN` — act on it.
     *  - `>= PLAUSIBLE` — best guess; act only if nothing else scores close.
     *  - below that — treat as no match and ask.
     */
    const val CERTAIN = 0.80
    const val PLAUSIBLE = 0.55

    /** Words that carry no identity and shouldn't sway a score either way. */
    private val NOISE = setOf(
        "the", "a", "an", "app", "mobile", "free", "lite", "go", "beta", "pro",
        "plus", "hd", "new", "official", "my"
    )

    /**
     * Strips everything that isn't identity: case, accents, punctuation, spacing.
     *
     * "Móyò-Adé" and "moyo ade" both become "moyo ade", which is what a person
     * means when they say either out loud.
     */
    fun normalise(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val stripped = buildString {
            for (c in decomposed) {
                when {
                    // Combining accent marks.
                    c.code in 0x0300..0x036F -> Unit
                    c.isLetterOrDigit() -> append(c.lowercaseChar())
                    c.isWhitespace() -> append(' ')
                    // Punctuation becomes a gap, so "Moyo-Ade" tokenises like "Moyo Ade".
                    else -> append(' ')
                }
            }
        }
        return stripped.trim().replace(Regex("\\s+"), " ")
    }

    private fun tokens(raw: String): List<String> =
        normalise(raw).split(' ').filter { it.isNotBlank() }

    private fun meaningful(list: List<String>): List<String> =
        list.filterNot { it in NOISE }.ifEmpty { list }

    /**
     * How well [candidate] answers [query].
     *
     * Scores are capped below an exact match so a true equality always wins: given
     * "Moyo", the contact actually called Moyo must beat MoyOma every time.
     */
    fun score(query: String, candidate: String): Double {
        val q = normalise(query)
        val c = normalise(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0

        val qTokens = meaningful(tokens(query))
        val cTokens = meaningful(tokens(candidate))
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0.0

        // Same words, any order: "duty call of" still means Call of Duty.
        if (qTokens.toSet() == cTokens.toSet()) return 0.97

        // Whole-string containment either way. Both directions matter: the query can
        // be the longer one ("call of duty mobile" for "Call of Duty") or the shorter
        // ("moyo" for "MoyOma"), and only handling one was the original bug.
        if (c.startsWith(q)) return 0.95 - lengthPenalty(q, c)
        if (q.startsWith(c)) return 0.93 - lengthPenalty(c, q)
        if (c.contains(q)) return 0.88 - lengthPenalty(q, c)
        if (q.contains(c)) return 0.86 - lengthPenalty(c, q)

        // Token overlap, measured against whichever side has fewer words — so a
        // two-word query fully covered by a five-word label still scores well.
        val overlap = qTokens.count { qt ->
            cTokens.any { ct -> ct == qt || ct.startsWith(qt) || qt.startsWith(ct) }
        }
        if (overlap > 0) {
            val coverage = overlap.toDouble() / minOf(qTokens.size, cTokens.size)
            val breadth = overlap.toDouble() / maxOf(qTokens.size, cTokens.size)
            // Coverage dominates, breadth breaks ties: "call of duty" against
            // "Call of Duty: Warzone" should beat it against "Duty Roster".
            val base = 0.55 + 0.30 * coverage + 0.10 * breadth
            return base.coerceAtMost(0.92)
        }

        // Initials: "cod" for "Call of Duty", "wa" for "WhatsApp Business".
        if (qTokens.size == 1 && cTokens.size > 1) {
            val initials = cTokens.mapNotNull { it.firstOrNull() }.joinToString("")
            if (initials == qTokens.first()) return 0.82
        }

        // Last resort, and tightly bounded: one slipped or dropped character, on
        // tokens long enough that a single edit can't turn one name into another.
        // "spotifi" reaches Spotify; "Mona" never reaches "Moyo".
        val q1 = qTokens.first()
        val c1 = cTokens.first()
        if (q1.length >= 5 && c1.length >= 5 && withinOneEdit(q1, c1)) return 0.70

        return 0.0
    }

    /** Longer leftovers mean a weaker match: "moyo" fits "Moyo B" better than "Moyola Enterprises". */
    private fun lengthPenalty(shorter: String, longer: String): Double {
        val extra = (longer.length - shorter.length).coerceAtLeast(0)
        return (extra * 0.012).coerceAtMost(0.30)
    }

    /** True when [a] and [b] differ by at most one insertion, deletion or substitution. */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** A candidate and how well it matched. */
    data class Hit<T>(val value: T, val label: String, val score: Double)

    /**
     * The outcome of a lookup, kept separate so callers can't accidentally treat a
     * coin-flip as a decision.
     */
    sealed class Result<out T> {
        data class Found<T>(val hit: Hit<T>) : Result<T>()

        /** Several candidates scored alike. The caller must ask rather than pick. */
        data class Ambiguous<T>(val hits: List<Hit<T>>) : Result<T>()

        object None : Result<Nothing>()
    }

    /**
     * Best match for [query] among [candidates].
     *
     * Returns [Result.Ambiguous] when the runner-up is within [tieBand] of the
     * winner and neither is exact — because with two contacts called Moyo, picking
     * one and dialling is worse than taking a second to ask which.
     */
    fun <T> best(
        query: String,
        candidates: List<T>,
        tieBand: Double = 0.06,
        label: (T) -> String
    ): Result<T> {
        if (query.isBlank()) return Result.None

        val scored = candidates
            .map { Hit(it, label(it), score(query, label(it))) }
            .filter { it.score >= PLAUSIBLE }
            .sortedByDescending { it.score }

        val top = scored.firstOrNull() ?: return Result.None
        if (top.score >= 1.0) return Result.Found(top)

        val rivals = scored.drop(1).takeWhile { top.score - it.score <= tieBand }
        // Same underlying thing under two labels is not a real ambiguity.
        val distinct = rivals.filterNot { normalise(it.label) == normalise(top.label) }
        return if (distinct.isNotEmpty()) {
            Result.Ambiguous(listOf(top) + distinct.take(4))
        } else {
            Result.Found(top)
        }
    }
}
