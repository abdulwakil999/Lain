package com.lain.assistant.data

import java.util.Locale

/**
 * How Lain decides that two pieces of text are about the same thing.
 *
 * Retrieval used to be term overlap over stemmed words, which is grep with extra
 * steps: a stored fact reading "his mother is called Amina" was invisible to
 * "what's my mum's name", because the two share no word. The user experiences that
 * as memory that does not work, and there is no way to tell from inside the app —
 * nothing errors, the fact is simply never retrieved.
 *
 * Three layers, cheapest first:
 *
 *  1. **Stemming**, so "projects" reaches "project".
 *  2. **Concepts**, so "mum", "mother" and "mum's" reach the same token as each
 *     other. This is the layer that makes matching about meaning rather than
 *     spelling, and it is a hand-written lexicon — not embeddings. It knows the few
 *     hundred words an assistant's memories are actually made of and nothing else,
 *     which is the honest trade: no model, no download, no inference cost, and a
 *     hard edge where the lexicon stops.
 *  3. **Trigram similarity**, for the near-misses a lexicon cannot enumerate:
 *     spacing ("whatsapp" / "whats app") and endings ("Amina" / "Aminas"). It is
 *     weak on short words with a letter changed in the middle — "Ameena" scores
 *     0.25 against "Amina" and will not be rescued — and that limit is left in
 *     place rather than lowering the bar, because a threshold loose enough to catch
 *     it also catches everything else.
 *
 * All of it is string work over a few hundred stored facts. It runs on every turn
 * and costs microseconds, which is the point: the alternative was a model call, and
 * a model call to search your own database is the thing this app exists not to do.
 */
object TextIndex {

    /** Ignored when scoring: they match everything and rank nothing. */
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been", "being",
        "to", "of", "in", "on", "at", "for", "with", "about", "from", "by", "it", "this", "that",
        "these", "those", "i", "you", "me", "my", "your", "we", "us", "our", "he", "she", "they",
        "them", "his", "her", "do", "does", "did", "can", "could", "would", "should", "will",
        "shall", "have", "has", "had", "what", "when", "where", "who", "why", "how", "not", "no",
        "yes", "if", "then", "than", "so", "just", "get", "got", "let", "like", "want", "need",
        "please", "up", "out"
    )

    /** Longest first, so "ities" is stripped before "ies". */
    private val STEM_SUFFIXES = listOf(
        "ities", "ation", "ings", "ies", "ing", "ers", "ed", "es", "er", "ly", "s"
    )

    /**
     * Words that mean the same thing for the purpose of finding a memory.
     *
     * Grouped by what a person would be storing facts about, and deliberately not
     * exhaustive — every entry has to earn its place, because a group that is too
     * broad silently merges two different subjects and returns the wrong fact, which
     * is worse than returning none. "Car" and "bike" are both transport and are not
     * in a group together for exactly that reason.
     *
     * The first word of each group is the concept every member resolves to.
     */
    private val GROUPS: List<List<String>> = listOf(
        // People, which is most of what anyone tells an assistant.
        listOf("mother", "mum", "mom", "mama", "mummy", "mommy", "madre"),
        listOf("father", "dad", "papa", "daddy", "padre"),
        listOf("sibling", "brother", "sister", "bro", "sis"),
        listOf("partner", "wife", "husband", "spouse", "girlfriend", "boyfriend", "fiance", "fiancee"),
        listOf("child", "son", "daughter", "kid", "baby"),
        listOf("friend", "mate", "buddy", "pal"),
        listOf("boss", "manager", "supervisor", "lead"),
        listOf("colleague", "coworker", "workmate", "teammate"),
        listOf("teacher", "lecturer", "professor", "tutor", "instructor"),
        listOf("doctor", "gp", "physician", "clinic", "hospital"),
        listOf("relative", "uncle", "aunt", "cousin", "grandma", "grandmother", "grandpa", "grandfather", "granny"),
        listOf("neighbour", "neighbor", "landlord", "roommate", "flatmate", "housemate"),

        // Places and the words for them.
        listOf("home", "house", "flat", "apartment", "place", "address"),
        listOf("work", "job", "office", "workplace", "employer", "company", "firm"),
        listOf("school", "university", "uni", "college", "campus", "class"),
        listOf("shop", "store", "market", "supermarket", "mall"),
        listOf("restaurant", "cafe", "diner", "eatery"),
        listOf("gym", "fitness", "workout", "training"),
        listOf("church", "mosque", "temple", "masjid"),

        // Study and work, which is what she is now asked for most.
        listOf("exam", "test", "quiz", "midterm", "final", "paper"),
        listOf("assignment", "homework", "coursework", "essay", "project", "submission"),
        listOf("deadline", "due", "cutoff"),
        listOf("lecture", "lesson", "seminar", "tutorial"),
        listOf("meeting", "standup", "catchup", "appointment", "interview"),
        listOf("course", "module", "subject", "programme", "program", "degree"),

        // Time, because a date is the most common thing worth remembering.
        listOf("birthday", "bday", "born", "anniversary"),
        listOf("holiday", "vacation", "leave", "break", "trip"),
        listOf("weekend", "saturday", "sunday"),
        listOf("morning", "am", "sunrise", "dawn"),
        listOf("evening", "night", "pm", "tonight"),

        // Things and habits.
        listOf("phone", "mobile", "handset", "device"),
        listOf("laptop", "computer", "pc", "macbook", "desktop"),
        listOf("car", "vehicle", "motor", "automobile"),
        listOf("money", "cash", "salary", "wage", "pay", "budget", "rent"),
        listOf("food", "meal", "dinner", "lunch", "breakfast", "eat"),
        listOf("medicine", "medication", "tablet", "pill", "prescription", "dose"),
        listOf("music", "song", "track", "playlist", "album"),
        listOf("film", "movie", "cinema", "show", "series"),
        listOf("football", "soccer", "match", "fixture"),
        listOf("prayer", "salah", "namaz", "worship"),
        listOf("quran", "koran", "surah", "surat", "recitation"),

        // How people say they feel about something, which is what a preference is.
        listOf("like", "love", "enjoy", "prefer", "favourite", "favorite", "fond"),
        listOf("dislike", "hate", "loathe", "detest", "despise", "avoid"),
        listOf("allergic", "allergy", "intolerant", "intolerance"),

        // Being contacted, which is what half of her memories are for.
        listOf("call", "ring", "dial", "phonecall"),
        listOf("message", "text", "sms", "whatsapp", "dm", "chat"),
        listOf("email", "mail", "inbox")
    )

    /** word -> the concept it belongs to. Built once; every entry is stemmed the same way lookups are. */
    private val CONCEPTS: Map<String, String> = buildMap {
        for (group in GROUPS) {
            val concept = "~" + group.first()
            for (word in group) {
                put(word, concept)
                put(stem(word), concept)
            }
        }
    }

    /**
     * The meaningful words of a string, stemmed.
     *
     * Punctuation-split rather than word-split so "mum's" yields "mum", and
     * `+`, `#` and `.` survive because "c++", "c#" and "node.js" are things people
     * store facts about.
     */
    fun tokens(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9+#.]+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .map { stem(it) }
            .toSet()

    /**
     * The same words, collapsed onto their concepts.
     *
     * A word the lexicon does not know maps to itself, so this is never *worse* than
     * plain token matching — it can only add hits, never remove one.
     */
    fun concepts(text: String): Set<String> =
        tokens(text).map { CONCEPTS[it] ?: it }.toSet()

    /**
     * Crude suffix stripping, so "projects" matches "project" and "running"
     * matches "run".
     *
     * A full stemmer would be overkill: this is English suffix trimming with a
     * length guard so short words survive intact, which recovers most of what exact
     * matching loses for a few microseconds and no dependency.
     */
    fun stem(word: String): String {
        if (word.length <= 4) return word
        for (suffix in STEM_SUFFIXES) {
            if (word.length - suffix.length >= 3 && word.endsWith(suffix)) {
                val base = word.dropLast(suffix.length)
                // "running" -> "runn" -> "run": undo the doubled consonant.
                return if (base.length > 3 && base.last() == base[base.length - 2] && base.last() !in "sl") {
                    base.dropLast(1)
                } else {
                    base
                }
            }
        }
        return word
    }


    /**
     * How alike two strings look, 0 to 1, by shared three-character runs.
     *
     * The layer under the lexicon. It catches what no word list can enumerate —
     * "whatsapp" against "whats app", a plural against its singular — without
     * knowing anything about language, which is why it is last: it is the loosest of
     * the three and the easiest to fool, so it only ever adds a small amount of
     * score. Short words differing in the middle score low; that is the shape of the
     * measure, not a bug to tune around.
     */
    fun similarity(a: String, b: String): Double {
        val left = trigrams(a)
        val right = trigrams(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { it in right }
        return shared.toDouble() / minOf(left.size, right.size)
    }

    private fun trigrams(text: String): List<String> {
        val flat = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (flat.length < 3) return emptyList()
        return (0..flat.length - 3).map { flat.substring(it, it + 3) }
    }
}
