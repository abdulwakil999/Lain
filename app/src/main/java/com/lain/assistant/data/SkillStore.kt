package com.lain.assistant.data

import android.content.Context
import com.lain.assistant.data.db.LainDatabase
import com.lain.assistant.data.db.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The skills Lain has been taught, and how she finds the right one.
 *
 * This is the layer that turns an assistant into one that gets better rather than
 * one that merely remembers. A fact makes an answer more accurate; a skill makes a
 * whole task disappear into two words, permanently, on the phone, whether or not
 * anyone is paying for a model that month.
 *
 * They compound because a skill's steps are plain text and a step may name another
 * skill. "Wind down" can be three device actions; "leaving the house" can be *wind
 * down*, plus lock up, plus text whoever is expecting you. Nothing special is needed
 * to make that work — the steps of the outer skill are expanded before they are
 * acted on, and the inner one is looked up by name like anything else.
 */
class SkillStore(context: Context) {

    companion object {
        /** How deep a skill may reach into other skills before it is a loop. */
        const val MAX_DEPTH = 4

        /** A trigger has to be at least this close before it counts as a match. */
        private const val MATCH_THRESHOLD = 0.6
    }

    private val dao by lazy { LainDatabase.get(context).skills() }

    suspend fun all(): List<SkillEntity> = withContext(Dispatchers.IO) {
        runCatching { dao.all() }.getOrDefault(emptyList())
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        runCatching { dao.count() }.getOrDefault(0)
    }

    suspend fun byName(name: String): SkillEntity? = withContext(Dispatchers.IO) {
        runCatching { dao.byName(name.trim().lowercase()) }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching { dao.deleteById(id) }
        Unit
    }

    /**
     * Teaches a skill, or replaces one of the same name.
     *
     * Replacing rather than duplicating: "teach me that again, properly" is the
     * common second attempt, and ending up with two skills called the same thing
     * means the wrong one runs half the time and nobody can tell which.
     */
    suspend fun teach(
        name: String,
        steps: String,
        triggers: List<String> = emptyList(),
        fullyLocal: Boolean = false
    ): SkillEntity? = withContext(Dispatchers.IO) {
        val clean = name.trim().lowercase().take(60)
        if (clean.isBlank() || steps.isBlank()) return@withContext null

        val existing = runCatching { dao.byName(clean) }.getOrNull()
        val entity = SkillEntity(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = clean,
            triggers = (triggers.map { it.trim().lowercase() } + clean)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n"),
            steps = steps.trim(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            // Kept across a re-teach. Someone correcting a skill they use daily has
            // not stopped using it, and resetting the count would bury it.
            lastUsedAt = existing?.lastUsedAt ?: 0,
            uses = existing?.uses ?: 0,
            fullyLocal = fullyLocal
        )
        runCatching { dao.upsert(entity) }.getOrNull() ?: return@withContext null
        entity
    }

    /**
     * The skill a message is asking for, if any.
     *
     * Matched on concepts rather than on the exact words, because nobody says a
     * trigger the same way twice — the skill taught as "wind down" has to answer to
     * "wind down for the night" and "time to wind down". Scored, with a floor, so a
     * passing mention of one word does not fire a procedure that changes settings.
     */
    suspend fun match(message: String): SkillEntity? = withContext(Dispatchers.IO) {
        val skills = runCatching { dao.all() }.getOrDefault(emptyList())
        if (skills.isEmpty()) return@withContext null

        val asked = TextIndex.concepts(message)
        if (asked.isEmpty()) return@withContext null
        val normalised = message.trim().lowercase()

        val best = skills
            .map { skill -> skill to score(skill, normalised, asked) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first

        best?.let { runCatching { dao.markUsed(it.id) } }
        best
    }

    /**
     * How well a message asks for a skill, 0 to 1.
     *
     * An exact trigger is unambiguous and scores outright. Otherwise it is the share
     * of the trigger's own concepts that the message covers — the trigger has to be
     * mostly present, not merely touched, which is what stops "set an alarm" from
     * firing a skill whose steps happen to include one.
     */
    private fun score(skill: SkillEntity, normalised: String, asked: Set<String>): Double {
        var best = 0.0
        for (trigger in skill.triggers.lineSequence()) {
            val phrase = trigger.trim()
            if (phrase.isBlank()) continue
            if (normalised == phrase) return 1.0
            if (normalised.contains(phrase)) {
                best = maxOf(best, 0.9)
                continue
            }
            val wanted = TextIndex.concepts(phrase)
            if (wanted.isEmpty()) continue
            val covered = wanted.count { it in asked }.toDouble() / wanted.size
            best = maxOf(best, covered)
        }
        return best
    }

    /**
     * A skill's steps with any skill it names expanded in place.
     *
     * Depth-limited and cycle-guarded, because two skills that reference each other
     * is an easy thing for a person to create by accident and an infinite loop is a
     * poor way to find out. A reference that cannot be resolved is left as written —
     * the model reads the step as prose and usually does the right thing anyway,
     * which beats erroring out over a name that was never a skill.
     */
    suspend fun expand(skill: SkillEntity, depth: Int = 0, seen: Set<String> = emptySet()): String =
        withContext(Dispatchers.IO) {
            if (depth >= MAX_DEPTH) return@withContext skill.steps
            val visited = seen + skill.name
            val known = runCatching { dao.all() }.getOrDefault(emptyList())
                .filterNot { it.name in visited }

            var text = skill.steps
            for (other in known) {
                // Word-bounded so a skill called "call" does not rewrite the word
                // "call" everywhere it appears in another skill's steps.
                val mention = Regex("\\b${Regex.escape(other.name)}\\b", RegexOption.IGNORE_CASE)
                if (!mention.containsMatchIn(text)) continue
                val inner = expand(other, depth + 1, visited)
                text = mention.replace(text) { "(${other.name}: $inner)" }
            }
            text
        }
}
