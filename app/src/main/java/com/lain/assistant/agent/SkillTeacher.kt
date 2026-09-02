package com.lain.assistant.agent

/**
 * Recognises being taught, without a model.
 *
 * Teaching has to be cheap or nobody does it. If saying "when I say wind down, put
 * the phone on Do Not Disturb and drop the brightness" costs a network round trip
 * and a model that might get the parse wrong, the feature exists on paper and goes
 * unused. These are the sentence shapes people actually reach for, matched on the
 * device in microseconds and confirmed back so a wrong parse is visible immediately
 * rather than the next time the skill runs.
 */
object SkillTeacher {

    /** @param name the handle; [steps] the procedure in the user's own words. */
    data class Taught(val name: String, val steps: String, val triggers: List<String>)

    private val PATTERNS = listOf(
        // "when I say wind down, do X"
        Regex(
            "^(?:when|whenever) i say (?<name>.{2,60}?)[,:]\\s*(?<steps>.{4,600})$",
            RegexOption.IGNORE_CASE
        ),
        // "learn a skill called wind down: do X"
        Regex(
            "^(?:learn|remember|save)(?: (?:a|this|the))? (?:new )?skill(?: called| named)? " +
                "(?<name>.{2,60}?)[,:]\\s*(?<steps>.{4,600})$",
            RegexOption.IGNORE_CASE
        ),
        // "teach you how to wind down: do X"
        Regex(
            "^(?:let me )?teach (?:you )?(?:how to )?(?<name>.{2,60}?)[,:]\\s*(?<steps>.{4,600})$",
            RegexOption.IGNORE_CASE
        ),
        // "from now on, wind down means: do X"
        Regex(
            "^(?:from now on,? )?(?<name>.{2,60}?) means[,:]\\s*(?<steps>.{4,600})$",
            RegexOption.IGNORE_CASE
        )
    )

    /**
     * @return what was taught, or null when this was not a lesson.
     *
     * Deliberately strict about the separator. Without a colon or a comma there is no
     * reliable line between the name and the procedure, and a skill filed under half
     * a sentence is worse than one not filed at all — it will never match anything
     * again, and the user will not know why.
     */
    fun parse(message: String): Taught? {
        val text = message.trim().removeSuffix(".")
        if (text.length < 12 || text.length > 700) return null

        for (pattern in PATTERNS) {
            val match = pattern.find(text) ?: continue
            val name = match.groups["name"]?.value?.trim()?.trim('"', '\'')?.lowercase() ?: continue
            val steps = match.groups["steps"]?.value?.trim() ?: continue
            if (name.isBlank() || steps.isBlank()) continue
            // A name that is itself a sentence is a misparse, not a skill handle.
            if (name.length > 60 || name.split(" ").size > 6) continue
            return Taught(name = name, steps = steps, triggers = listOf(name))
        }
        return null
    }

    /** "forget the wind down skill", "unlearn wind down". */
    fun parseForget(message: String): String? {
        val text = message.trim().removeSuffix(".").lowercase()
        val patterns = listOf(
            Regex("^(?:forget|unlearn|delete|remove)(?: the)? (?<name>.{2,60}?)(?: skill)?$"),
            Regex("^(?:forget|unlearn) how to (?<name>.{2,60})$")
        )
        for (pattern in patterns) {
            val name = pattern.find(text)?.groups?.get("name")?.value?.trim() ?: continue
            if (name.isNotBlank() && name.split(" ").size <= 6) return name
        }
        return null
    }
}
