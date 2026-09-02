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

/**
 * Something Lain was taught to do, kept for good.
 *
 * The difference between an assistant that is useful and one that gets *more* useful
 * is whether last week's explanation is still worth anything today. Everything else
 * she holds is a fact; this is a procedure — "when I say wind down, put the phone on
 * Do Not Disturb, drop the brightness and set an alarm for seven" — taught once and
 * hers from then on.
 *
 * [steps] is kept as the user's own words rather than compiled into anything. A
 * parsed representation would be faster and would rot: it can only encode the
 * actions that existed the day it was written, and the whole point is that a skill
 * outlives the version of the app that learned it. Plain text stays executable by
 * whatever Lain can do next year.
 *
 * [uses] and [lastUsedAt] are what let the useful ones rise and the abandoned ones be
 * found and dropped, so a hundred skills do not become a hundred things to search.
 */
@Entity(tableName = "skills", indices = [Index("name"), Index("lastUsedAt")])
data class SkillEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** What it is called, lowercased. The handle the user and other skills refer to. */
    val name: String,
    /** Phrases that should invoke it, newline-separated. The name always counts. */
    val triggers: String,
    /** The procedure, in the user's own words. */
    val steps: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0,
    val uses: Int = 0,
    /**
     * True when every step resolved to something Lain can do with no model.
     *
     * Set when the skill is taught and re-checked when it runs. A skill that is
     * entirely local runs offline and instantly, which is worth knowing about rather
     * than discovering by trying.
     */
    val fullyLocal: Boolean = false
)
