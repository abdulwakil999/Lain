package com.lain.assistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** A distinct thread of conversation. Survives process death and app restarts. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Rolling compression of turns that have aged out of the live context. This is
     * what lets "what did we decide earlier?" still work after the raw messages are
     * no longer sent to the model.
     */
    val summary: String? = null,
    /** How many messages are already represented by [summary], so we never re-summarise them. */
    val summarizedUpTo: Int = 0
)

/**
 * One stored turn. This is the user-visible transcript AND the source for rebuilding
 * model context — deliberately kept separate from whatever slice we choose to send,
 * so trimming context can never destroy history.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index(value = ["conversationId", "createdAt"])]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    /** user | assistant | tool | system */
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Serialized tool calls for assistant turns; null otherwise. */
    val toolCallsJson: String? = null,
    /** Which tool call this message answers, for tool turns. */
    val toolCallId: String? = null,
    /** Hidden from the transcript UI but still part of model context (e.g. captured screens). */
    val hidden: Boolean = false
)

/**
 * A durable fact about the user, worth carrying into future conversations.
 * Retrieved selectively by relevance — never dumped wholesale into a prompt.
 */
@Entity(tableName = "memories", indices = [Index("category"), Index("updatedAt")])
data class MemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** identity | project | preference | person | goal | technical | event | other */
    val category: String,
    /** Short canonical subject used for dedup/update matching, e.g. "current project". */
    val subject: String,
    val fact: String,
    /** 1 (trivia) .. 5 (core). Drives eviction and retrieval ordering. */
    val importance: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Conversation this was learned in, when known. */
    val sourceConversationId: String? = null,
    /** Bumped whenever the memory is actually used, so useful facts survive eviction. */
    val useCount: Int = 0
)

/**
 * One thing Lain did, and why she did it.
 *
 * Kept because an assistant with Accessibility access can read a screen, send a
 * message and change a setting, and the only evidence a user has of that is her own
 * account of it in the chat — which is exactly the thing they cannot check. This is
 * the record that does not depend on her describing it accurately: written by the
 * dispatcher from the actual outcome, not by the model from what it believes
 * happened.
 *
 * [goal] is the request the action was taken in service of, so a line reads as "did
 * this, because you asked for that" rather than as a bare event.
 *
 * Message bodies are deliberately not stored. Knowing a text went to a contact is
 * the auditable fact; keeping a second copy of everything ever sent would make this
 * a bigger privacy liability than the thing it exists to make accountable.
 */
@Entity(tableName = "action_log", indices = [Index("at")])
data class ActionLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
    /** The tool or local action, by name. */
    val action: String,
    /** What it was aimed at — an app, a contact, a setting. Never message content. */
    val detail: String,
    val succeeded: Boolean,
    /** What came back, truncated. The failure reason when it failed. */
    val outcome: String,
    /** The user's request this was part of. */
    val goal: String
)
