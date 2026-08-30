package com.lain.assistant.data

import android.content.Context
import com.lain.assistant.data.db.ConversationEntity
import com.lain.assistant.data.db.LainDatabase
import com.lain.assistant.data.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable conversation storage.
 *
 * The stored transcript and the slice sent to the model are deliberately separate
 * things: history is append-only and complete, while context is a rebuilt view of
 * it (summary + recent turns). That separation is what stops context trimming from
 * destroying anything the user might later refer back to.
 */
class ConversationStore(context: Context) {

    private val db = LainDatabase.get(context)
    private val conversations = db.conversations()
    private val messages = db.messages()

    /**
     * Past messages that are about the same thing as [query], newest first.
     *
     * The half of "search my history" that a keyword match cannot do: asked about
     * "the flat", it finds the turn where the user said "apartment". Scored with
     * [TextIndex] over a bounded window of recent messages rather than by SQL LIKE,
     * which would only ever find the word that was typed.
     *
     * @param limit how many hits to return, not how many messages to consider.
     */
    suspend fun searchMessages(query: String, limit: Int = 6): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            val wanted = TextIndex.concepts(query)
            if (wanted.isEmpty()) return@withContext emptyList()

            messages.recentEverywhere(SEARCH_WINDOW)
                .asSequence()
                .filter { it.content.isNotBlank() }
                .map { message ->
                    val overlap = wanted.intersect(TextIndex.concepts(message.content)).size
                    message to overlap + TextIndex.similarity(query, message.content)
                }
                .filter { it.second >= 1.0 }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
        }



    /** Live tail kept verbatim. Older turns survive via the rolling summary. */
    companion object {
        const val RECENT_WINDOW = 24

        /**
         * How far back a search looks.
         *
         * Far enough to cover the history anyone actually refers back to, small
         * enough that scoring it is free. Searching everything ever said would get
         * slower every week the app is used, which is the wrong shape for something
         * that runs while a person waits.
         */
        const val SEARCH_WINDOW = 400
        /** Compress once the backlog behind the window gets this deep. */
        const val SUMMARIZE_THRESHOLD = 12
        /** Captured screens older than this are dropped; they're heavy and quickly stale. */
        const val HIDDEN_TTL_MS = 10 * 60 * 1000L
    }

    suspend fun activeConversation(): ConversationEntity = withContext(Dispatchers.IO) {
        conversations.mostRecent() ?: ConversationEntity(title = "New conversation").also {
            conversations.upsert(it)
        }
    }

    suspend fun startNew(title: String = "New conversation"): ConversationEntity = withContext(Dispatchers.IO) {
        ConversationEntity(title = title).also { conversations.upsert(it) }
    }

    fun observeVisibleMessages(conversationId: String) = messages.observeVisible(conversationId)
    fun observeConversations() = conversations.observeAll()

    suspend fun append(message: MessageEntity) = withContext(Dispatchers.IO) {
        messages.insert(message)
        conversations.touch(message.conversationId)
        // First real user line becomes the thread's title, so history is browsable.
        val convo = conversations.byId(message.conversationId)
        if (convo != null && convo.title == "New conversation" && message.role == "user") {
            conversations.upsert(convo.copy(title = message.content.take(60), updatedAt = System.currentTimeMillis()))
        }
    }

    /** Deletes a single message, so a deleted turn stops reaching the model too. */
    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        messages.deleteById(id)
    }

    suspend fun recentMessages(conversationId: String, limit: Int = RECENT_WINDOW): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            messages.pruneHiddenBefore(conversationId, System.currentTimeMillis() - HIDDEN_TTL_MS)
            messages.recent(conversationId, limit).reversed()
        }

    suspend fun allMessages(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) { messages.allFor(conversationId) }

    suspend fun messageCount(conversationId: String): Int =
        withContext(Dispatchers.IO) { messages.count(conversationId) }

    suspend fun conversation(conversationId: String): ConversationEntity? =
        withContext(Dispatchers.IO) { conversations.byId(conversationId) }

    /**
     * Returns the block of older turns that should be folded into the summary, or
     * null when there's nothing worth compressing yet.
     */
    suspend fun pendingSummaryWindow(conversationId: String): List<MessageEntity>? =
        withContext(Dispatchers.IO) {
            val convo = conversations.byId(conversationId) ?: return@withContext null
            val total = messages.count(conversationId)
            val backlog = total - RECENT_WINDOW - convo.summarizedUpTo
            if (backlog < SUMMARIZE_THRESHOLD) return@withContext null
            messages.window(conversationId, offset = convo.summarizedUpTo, limit = backlog)
        }

    suspend fun saveSummary(conversationId: String, summary: String, coveredUpTo: Int) =
        withContext(Dispatchers.IO) {
            conversations.saveSummary(conversationId, summary.take(4000), coveredUpTo)
        }

    suspend fun summaryOf(conversationId: String): String? =
        withContext(Dispatchers.IO) { conversations.byId(conversationId)?.summary }
}
