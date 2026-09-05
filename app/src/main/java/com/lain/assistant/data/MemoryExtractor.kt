package com.lain.assistant.data

/**
 * Facts pulled out of what the user just said, with no model involved.
 *
 * Memory used to depend on someone saying "remember that…", or on a housekeeping
 * request to the model that ran on roughly one turn in three, skipped short
 * messages, skipped anything that looked like a command, and never ran at all on
 * the local fast path. So "my mum's name is Amina" — the exact kind of thing an
 * assistant exists to hold on to — was usually answered and then dropped. The user
 * finds out weeks later, by being asked something they already said.
 *
 * These patterns are the ones worth catching deterministically: the sentence shapes
 * people actually use to state a durable fact about themselves. It runs on every
 * user turn, on the local path too, and costs a few dozen regex matches — so on a
 * free model it is the difference between memory that works and memory that costs
 * an extra request to maybe work.
 *
 * The model-driven extractor stays for everything subtler than this. This is the
 * floor, not the ceiling.
 *
 * Two rules keep it from filling the store with rubbish. It reads only the user's
 * own words — never Lain's reply, or she learns her own inventions as fact. And it
 * ignores questions, because "is my mum's name Amina?" is someone asking, not
 * someone telling.
 */
object MemoryExtractor {

    /**
     * @param subject the stable key this fact is filed under, so saying it again
     *   revises rather than duplicates.
     */
    data class Candidate(
        val subject: String,
        val fact: String,
        val category: MemoryCategory,
        val importance: Int
    )

    private class Rule(
        pattern: String,
        val subject: String,
        val category: MemoryCategory,
        val importance: Int,
        /** Builds the stored sentence from the captured group. */
        val fact: (String) -> String
    ) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /**
     * Longest and most specific first — the first rule that matches wins, so
     * "my mum's name is X" must be tried before the looser relative patterns.
     */
    private val RULES = listOf(
        // ---------------------------------------------------------- identity
        Rule("\\bmy name is ([\\p{L}][\\p{L}'\\- ]{1,40})", "name", MemoryCategory.IDENTITY, 5) {
            "Their name is $it"
        },
        // "call me Ade" is a fact; "call me an ambulance" is an instruction, which
        // the article rules out. Two words at most, or every request starting with
        // "call me" becomes an identity claim.
        Rule(
            "^call me (?!a |an |the |back|later|when|if|in |at |on )" +
                "([\\p{L}][\\p{L}'\\-]{1,20}(?: [\\p{L}'\\-]{1,20})?)\\.?$",
            "nickname", MemoryCategory.IDENTITY, 5
        ) {
            "They go by $it"
        },
        Rule("\\bi(?:'m| am) (\\d{1,2}) years old", "age", MemoryCategory.IDENTITY, 4) {
            "They are $it years old"
        },
        Rule("\\bmy birthday is (?:on )?([\\p{L}\\d][\\p{L}\\d ,/'\\-]{2,40})", "birthday", MemoryCategory.IDENTITY, 5) {
            "Their birthday is $it"
        },
        Rule("\\bi was born (?:on|in) ([\\p{L}\\d][\\p{L}\\d ,/'\\-]{2,40})", "birthday", MemoryCategory.IDENTITY, 5) {
            "They were born $it"
        },
        Rule("\\bi live in ([\\p{L}][\\p{L}' ,\\-]{2,50})", "home", MemoryCategory.IDENTITY, 4) {
            "They live in $it"
        },
        Rule("\\bmy address is ([\\p{L}\\d][\\p{L}\\d ,/'\\-]{4,70})", "address", MemoryCategory.IDENTITY, 4) {
            "Their address is $it"
        },
        Rule("\\bi (?:work at|work for|am employed at) ([\\p{L}\\d][\\p{L}\\d&' \\-]{1,50})", "work", MemoryCategory.IDENTITY, 4) {
            "They work at $it"
        },
        Rule("\\bi(?:'m| am) (?:a|an) ([\\p{L}][\\p{L}' \\-]{2,40}?)(?: by (?:trade|profession))?$", "job", MemoryCategory.IDENTITY, 3) {
            "They are a $it"
        },
        Rule("\\bi (?:study|am studying|study for) ([\\p{L}][\\p{L}' \\-]{2,50})", "course", MemoryCategory.IDENTITY, 4) {
            "They study $it"
        },

        // ------------------------------------------------------------ people
        // The named relative, which is the single most useful thing she can hold.
        Rule(
            "\\bmy (mum|mom|mother|dad|father|brother|sister|wife|husband|partner|son|daughter|" +
                "boss|manager|friend|uncle|aunt|cousin|neighbour|neighbor|landlord|doctor|teacher|lecturer)" +
                "(?:'s)? name is ([\\p{L}][\\p{L}'\\- ]{1,40})",
            "", MemoryCategory.PERSON, 5
        ) { it },
        Rule(
            "\\b([\\p{L}][\\p{L}'\\-]{1,30}) is my (mum|mom|mother|dad|father|brother|sister|wife|" +
                "husband|partner|son|daughter|boss|manager|friend|uncle|aunt|cousin|neighbour|neighbor|" +
                "landlord|doctor|teacher|lecturer)\\b",
            "", MemoryCategory.PERSON, 5
        ) { it },

        // ------------------------------------------------------- preferences
        Rule("\\bmy favou?rite ([\\p{L}][\\p{L} '\\-]{1,30}?) is ([\\p{L}\\d][\\p{L}\\d '\\-]{1,40})", "", MemoryCategory.PREFERENCE, 4) { it },
        Rule("\\bi (?:really )?(?:hate|can't stand|cannot stand|despise) ([\\p{L}][\\p{L}\\d '\\-]{2,50})", "dislikes", MemoryCategory.PREFERENCE, 3) {
            "They hate $it"
        },
        Rule("\\bi(?:'m| am) allergic to ([\\p{L}][\\p{L}, '\\-]{2,50})", "allergy", MemoryCategory.PREFERENCE, 5) {
            "They are allergic to $it"
        },
        Rule("\\bi (?:always|usually) ([\\p{L}][\\p{L}\\d ,'\\-]{4,60})", "habit", MemoryCategory.PREFERENCE, 3) {
            "They always $it"
        },
        Rule("\\bi never ([\\p{L}][\\p{L}\\d ,'\\-]{4,60})", "avoids", MemoryCategory.PREFERENCE, 3) {
            "They never $it"
        },
        Rule("\\bi (?:prefer|would rather) ([\\p{L}][\\p{L}\\d ,'\\-]{2,60})", "preference", MemoryCategory.PREFERENCE, 3) {
            "They prefer $it"
        },

        // ------------------------------------------------------------ events
        Rule(
            "\\bmy (exam|test|flight|interview|appointment|wedding|deadline|presentation|graduation)" +
                " is (?:on |at )?([\\p{L}\\d][\\p{L}\\d :,/'\\-]{2,40})",
            "", MemoryCategory.EVENT, 4
        ) { it },

        // ------------------------------------------------- projects and goals
        Rule("\\bi(?:'m| am) working on ([\\p{L}\\d][\\p{L}\\d ,'\\-]{2,60})", "project", MemoryCategory.PROJECT, 4) {
            "They are working on $it"
        },
        Rule("\\bmy project is ([\\p{L}\\d][\\p{L}\\d ,'\\-]{2,60})", "project", MemoryCategory.PROJECT, 4) {
            "Their project is $it"
        },
        Rule("\\bi(?:'m| am) trying to ([\\p{L}][\\p{L}\\d ,'\\-]{4,60})", "goal", MemoryCategory.GOAL, 3) {
            "They are trying to $it"
        }
    )

    /**
     * Everything durable in one message. Empty for the overwhelming majority.
     *
     * @param message the user's own words, not Lain's reply.
     */
    fun extract(message: String): List<Candidate> {
        val text = message.trim()
        if (text.length < 8 || text.length > 400) return emptyList()
        // Someone asking whether a thing is true is not someone stating it.
        if (text.endsWith("?")) return emptyList()

        val found = mutableListOf<Candidate>()
        val claimed = mutableSetOf<String>()

        for (rule in RULES) {
            val match = rule.regex.find(text) ?: continue
            val groups = match.groupValues.drop(1).map { clean(it) }.filter { it.isNotBlank() }
            if (groups.isEmpty()) continue

            val candidate = when {
                // A two-group rule carries its own subject in the sentence: the
                // relative, the kind of event, the category of favourite.
                rule.subject.isEmpty() && groups.size >= 2 -> twoPart(rule, groups)
                rule.subject.isEmpty() -> null
                else -> Candidate(rule.subject, rule.fact(groups.first()), rule.category, rule.importance)
            } ?: continue

            // One fact per subject per message. Two rules firing on the same subject
            // means the looser one matched a fragment of the tighter one's sentence.
            if (!claimed.add(candidate.subject)) continue
            if (candidate.fact.length in 4..MemoryStore.MAX_FACT_CHARS) found += candidate
        }
        return found
    }

    /**
     * A rule whose sentence names its own subject.
     *
     * "My mum's name is Amina" and "Amina is my mum" carry the same two pieces in
     * opposite orders, so the relative is whichever half is a relationship word.
     */
    private fun twoPart(rule: Rule, groups: List<String>): Candidate? {
        val (first, second) = groups[0] to groups[1]
        return when (rule.category) {
            MemoryCategory.PERSON -> {
                val relationFirst = RELATIONS.contains(first.lowercase())
                val relation = if (relationFirst) first else second
                val name = if (relationFirst) second else first
                if (!RELATIONS.contains(relation.lowercase())) return null
                Candidate(
                    relation.lowercase(),
                    "Their $relation is called $name",
                    MemoryCategory.PERSON,
                    rule.importance
                )
            }

            MemoryCategory.PREFERENCE -> Candidate(
                "favourite $first".lowercase(),
                "Their favourite $first is $second",
                MemoryCategory.PREFERENCE,
                rule.importance
            )

            MemoryCategory.EVENT -> Candidate(
                first.lowercase(),
                "Their $first is on $second",
                MemoryCategory.EVENT,
                rule.importance
            )

            else -> null
        }
    }

    private val RELATIONS = setOf(
        "mum", "mom", "mother", "dad", "father", "brother", "sister", "wife", "husband",
        "partner", "son", "daughter", "boss", "manager", "friend", "uncle", "aunt",
        "cousin", "neighbour", "neighbor", "landlord", "doctor", "teacher", "lecturer"
    )

    /** Trailing punctuation and filler off the captured value, without touching what's inside it. */
    private fun clean(value: String): String =
        value.trim()
            .removeSuffix(".").removeSuffix(",").removeSuffix("!")
            .trim()
            .removeSuffix(" and").removeSuffix(" but").removeSuffix(" so")
            .trim()
}
