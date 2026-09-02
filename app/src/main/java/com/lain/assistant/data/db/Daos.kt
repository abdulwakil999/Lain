package com.lain.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET summary = :summary, summarizedUpTo = :upTo, updatedAt = :at WHERE id = :id")
    suspend fun saveSummary(id: String, summary: String, upTo: Int, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    /** Oldest-first, the natural order for rebuilding model context. */
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC")
    suspend fun allFor(cid: String): List<MessageEntity>

    /** The live tail sent verbatim to the model. */
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(cid: String, limit: Int): List<MessageEntity>

    /** Older turns eligible for compression into the rolling summary. */
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun window(cid: String, offset: Int, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :cid")
    suspend fun count(cid: String): Int

    @Query("SELECT * FROM messages WHERE conversationId = :cid AND hidden = 0 ORDER BY createdAt ASC")
    fun observeVisible(cid: String): Flow<List<MessageEntity>>

    /**
     * Removes one message the user deleted from the transcript.
     *
     * Safe to do mid-conversation: the model history is rebuilt from user and
     * assistant rows only, and tool rows are replayed as a plain recap rather than
     * as tool messages, so removing a visible turn cannot orphan a tool call.
     */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Drops stale captured screens; they're the most expensive thing to keep around. */
    @Query("DELETE FROM messages WHERE conversationId = :cid AND hidden = 1 AND createdAt < :before")
    suspend fun pruneHiddenBefore(cid: String, before: Long)

    /**
     * The most recent visible messages across every conversation, for searching.
     *
     * Bounded rather than complete, and ranked in memory afterwards rather than by
     * SQL LIKE — the point of the search is to match on meaning, and LIKE only ever
     * matches on spelling. The bound is what keeps that affordable: scoring a few
     * hundred rows is microseconds, scoring a year of chat is not.
     */
    @Query("SELECT * FROM messages WHERE hidden = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentEverywhere(limit: Int): List<MessageEntity>
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    /** Dedup/update hook: same subject in the same category means revise, not duplicate. */
    @Query("SELECT * FROM memories WHERE category = :category AND LOWER(subject) = LOWER(:subject) LIMIT 1")
    suspend fun findBySubject(category: String, subject: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE LOWER(subject) LIKE '%' || LOWER(:q) || '%' OR LOWER(fact) LIKE '%' || LOWER(:q) || '%'")
    suspend fun search(q: String): List<MemoryEntity>

    @Query("UPDATE memories SET useCount = useCount + 1 WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<String>)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memories")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /** Lowest-value memories first, for capacity eviction. */
    @Query("SELECT * FROM memories ORDER BY importance ASC, useCount ASC, updatedAt ASC LIMIT :limit")
    suspend fun weakest(limit: Int): List<MemoryEntity>
}

@Dao
interface ActionLogDao {

    @Insert
    suspend fun insert(entry: ActionLogEntity)

    @Query("SELECT * FROM action_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActionLogEntity>

    @Query("SELECT COUNT(*) FROM action_log")
    suspend fun count(): Int

    /** Drops everything past the newest [keep] rows. */
    @Query("DELETE FROM action_log WHERE id NOT IN (SELECT id FROM action_log ORDER BY at DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

@Dao
interface SkillDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY uses DESC, lastUsedAt DESC")
    suspend fun all(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): SkillEntity?

    @Query("UPDATE skills SET uses = uses + 1, lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun count(): Int
}
