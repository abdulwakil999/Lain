package com.lain.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.lain.assistant.data.db.LainDatabase
import com.lain.assistant.data.db.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

private val Context.memoryMigrationStore by preferencesDataStore(name = "lain_memory_migration")
private val MIGRATED = booleanPreferencesKey("migrated_v1")

/** Categories Lain files durable facts under. Kept small so weak models can pick correctly. */
enum class MemoryCategory {
    IDENTITY, PROJECT, PREFERENCE, PERSON, GOAL, TECHNICAL, EVENT, OTHER;

    companion object {
        fun parse(raw: String?): MemoryCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/**
 * Long-term memory: durable facts worth carrying between conversations.
 *
 * Two rules shape this design. First, memory is *retrieved*, never dumped —
 * injecting the whole store into every request wastes the context window that
 * free models can least afford. Second, revising beats appending: learning that a
 * project was renamed must update the existing fact, not leave two contradictory
 * ones for the model to pick between.
 */
class MemoryStore(private val context: Context) {

    companion object {
        /** Ceiling on stored facts; weakest are evicted past this. */
        const val MAX_MEMORIES = 300
        /** How many facts may be injected into a single request. */
        const val MAX_INJECTED = 12
        const val MAX_FACT_CHARS = 240

        /**
         * How alike a memory has to look before resemblance alone earns it a slot.
         *
         * Set high. This is the layer with no understanding behind it, so a low bar
         * fills the prompt with facts that merely share letters with the question.
         */
        private const val STRONG_RESEMBLANCE = 0.45
    }

    private val db by lazy { LainDatabase.get(context) }
    private val dao by lazy { db.memories() }

    val observeAll: Flow<List<MemoryEntity>> get() = dao.observeAll()

    /**
     * Brings across facts saved by the pre-Room key/value store so upgrading the app
     * never silently loses what Lain already knew. Runs once, guarded by a flag.
     */
    suspend fun migrateLegacyIfNeeded(legacy: LegacyMemoryReader) = withContext(Dispatchers.IO) {
        val done = context.memoryMigrationStore.data.map { it[MIGRATED] ?: false }.first()
        if (done) return@withContext
        runCatching {
            legacy.readAll().forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank() && dao.findBySubject(MemoryCategory.OTHER.name, key) == null) {
                    dao.upsert(
                        MemoryEntity(
                            category = inferCategory(key, value).name,
                            subject = key,
                            fact = value.take(MAX_FACT_CHARS),
                            importance = 3
                        )
                    )
                }
            }
        }
        context.memoryMigrationStore.edit { it[MIGRATED] = true }
    }

    /**
     * Saves or revises a fact. Matching an existing subject in the same category
     * updates it in place, which is what keeps "project X" from coexisting with
     * "project renamed to Y".
     */
    suspend fun remember(
        subject: String,
        fact: String,
        category: MemoryCategory = MemoryCategory.OTHER,
        importance: Int = 3,
        sourceConversationId: String? = null
    ): MemoryEntity = withContext(Dispatchers.IO) {
        val cleanSubject = subject.trim().lowercase(Locale.ROOT).take(80)
        val cleanFact = fact.trim().take(MAX_FACT_CHARS)
        val existing = dao.findBySubject(category.name, cleanSubject)
            ?: findSemanticDuplicate(cleanSubject, cleanFact)

        val entity = if (existing != null) {
            existing.copy(
                fact = cleanFact,
                category = category.name,
                importance = maxOf(existing.importance, importance),
                updatedAt = System.currentTimeMillis(),
                sourceConversationId = sourceConversationId ?: existing.sourceConversationId
            )
        } else {
            MemoryEntity(
                category = category.name,
                subject = cleanSubject,
                fact = cleanFact,
                importance = importance.coerceIn(1, 5),
                sourceConversationId = sourceConversationId
            )
        }
        dao.upsert(entity)
        enforceCapacity()
        entity
    }

    /** Catches near-duplicates that use a different wording for the same subject. */
    private suspend fun findSemanticDuplicate(subject: String, fact: String): MemoryEntity? {
        val subjectTokens = tokens(subject)
        if (subjectTokens.isEmpty()) return null
        return dao.all().firstOrNull { candidate ->
            val overlap = tokens(candidate.subject).intersect(subjectTokens)
            // Same subject words, or an almost identical fact, means revise rather than add.
            overlap.size >= subjectTokens.size && subjectTokens.isNotEmpty() ||
                candidate.fact.equals(fact, ignoreCase = true)
        }
    }

    suspend fun forget(query: String): Int = withContext(Dispatchers.IO) {
        val matches = dao.search(query.trim())
        matches.forEach { dao.deleteById(it.id) }
        matches.size
    }

    /**
     * Revises a stored memory in place, keeping its identity and creation time.
     *
     * Distinct from [remember], which matches on subject and may create. Editing by
     * id is what the UI and an explicit correction need: the user pointing at one
     * specific fact and saying "that's wrong, it's actually this". Creation time
     * and source conversation are preserved because they are provenance — when and
     * where Lain learned something stays true even after the fact is corrected.
     *
     * @return the updated memory, or null if that id no longer exists.
     */
    suspend fun edit(
        id: String,
        fact: String? = null,
        subject: String? = null,
        category: MemoryCategory? = null,
        importance: Int? = null
    ): MemoryEntity? = withContext(Dispatchers.IO) {
        val existing = dao.all().firstOrNull { it.id == id } ?: return@withContext null
        val updated = existing.copy(
            fact = fact?.trim()?.take(MAX_FACT_CHARS)?.ifBlank { null } ?: existing.fact,
            subject = subject?.trim()?.lowercase(Locale.ROOT)?.take(80)?.ifBlank { null } ?: existing.subject,
            category = category?.name ?: existing.category,
            importance = importance?.coerceIn(1, 5) ?: existing.importance,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
        updated
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) { dao.deleteById(id) }
    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
    suspend fun all(): List<MemoryEntity> = withContext(Dispatchers.IO) { dao.all() }

    /**
     * Relevance retrieval, on meaning rather than on spelling.
     *
     * Scores each memory against the message by shared *concepts* — see [TextIndex],
     * where "mum" and "mother" are the same token — then boosts importance, recency
     * and prior usefulness. Identity facts are always eligible because they colour
     * every reply.
     *
     * The change that matters is the filter at the bottom. It used to require a
     * literal shared word, which meant a stored fact could be a perfect answer and
     * still never be retrieved: nothing failed, the memory was simply silent. A
     * concept match or a close enough string now qualifies too.
     */
    suspend fun retrieveRelevant(
        query: String,
        limit: Int = MAX_INJECTED
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val stored = dao.all()
        if (stored.isEmpty()) return@withContext emptyList()

        val queryConcepts = TextIndex.concepts(query)
        val now = System.currentTimeMillis()

        val scored = stored.map { memory ->
            val text = memory.subject + " " + memory.fact
            val memoryConcepts = TextIndex.concepts(text)
            val overlap = if (queryConcepts.isEmpty()) 0 else queryConcepts.intersect(memoryConcepts).size
            // The loosest layer, and weighted like it: enough to rescue a typo or a
            // name spelled differently, never enough to outrank a real match.
            val resemblance = if (queryConcepts.isEmpty()) 0.0 else TextIndex.similarity(query, text)
            val ageDays = ((now - memory.updatedAt) / 86_400_000.0).coerceAtLeast(0.0)

            var score = overlap * 10.0
            score += resemblance * 6.0
            score += memory.importance * 2.0
            score += minOf(memory.useCount, 5) * 0.5
            score -= minOf(ageDays * 0.05, 4.0)
            // Who the user is stays relevant regardless of topic.
            if (memory.category == MemoryCategory.IDENTITY.name) score += 6.0
            Scored(memory, score, overlap, resemblance)
        }

        val picked = scored
            .filter { it.qualifies() }
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.memory }

        if (picked.isNotEmpty()) dao.markUsed(picked.map { it.id })
        picked
    }

    private class Scored(
        val memory: MemoryEntity,
        val score: Double,
        val conceptOverlap: Int,
        val resemblance: Double
    ) {
        /**
         * Whether this is worth a slot in the request at all.
         *
         * A high score alone is not enough — importance and recency can carry an
         * unrelated fact over the line, and an unrelated fact in the prompt is worse
         * than a missing one, because the model will try to use it. So there has to
         * be an actual connection to what was said: a shared concept, a close enough
         * resemblance, or the two categories that are relevant no matter the topic.
         */
        fun qualifies(): Boolean = score > 4.0 && (
            conceptOverlap > 0 ||
                resemblance >= STRONG_RESEMBLANCE ||
                memory.category == MemoryCategory.IDENTITY.name ||
                memory.importance >= 5
            )
    }

    private suspend fun enforceCapacity() {
        val total = dao.count()
        if (total <= MAX_MEMORIES) return
        dao.weakest(total - MAX_MEMORIES).forEach { dao.deleteById(it.id) }
    }

    private fun tokens(text: String): Set<String> = TextIndex.tokens(text)


    private fun inferCategory(key: String, value: String): MemoryCategory {
        val blob = (key + " " + value).lowercase(Locale.ROOT)
        return when {
            blob.contains("name") || blob.contains("call me") || blob.contains("nickname") -> MemoryCategory.IDENTITY
            blob.contains("project") || blob.contains("building") || blob.contains("app") -> MemoryCategory.PROJECT
            blob.contains("prefer") || blob.contains("favourite") || blob.contains("favorite") -> MemoryCategory.PREFERENCE
            blob.contains("number") || blob.contains("contact") || blob.contains("friend") -> MemoryCategory.PERSON
            blob.contains("goal") || blob.contains("want to") -> MemoryCategory.GOAL
            else -> MemoryCategory.OTHER
        }
    }
}

/** Adapter so migration doesn't hard-depend on the old repository's internals. */
fun interface LegacyMemoryReader {
    suspend fun readAll(): Map<String, String>
}
