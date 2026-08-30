package com.lain.assistant.data

import android.content.Context
import com.lain.assistant.data.db.ActionLogEntity
import com.lain.assistant.data.db.LainDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The record of what Lain actually did.
 *
 * Her own account of a task is the model's account, and the model is the part of
 * the system that can be wrong about it — a tool that failed and a tool that
 * succeeded look the same in a sentence. This is written from the dispatcher's
 * result instead: the same value that decided whether the step worked. So when the
 * chat says one thing and the log says another, the log is the one to believe.
 *
 * Rolling and capped. A log that grows forever is a log nobody scrolls and a
 * database that gets slower every month; [MAX_ENTRIES] is a few weeks of ordinary
 * use, and the oldest fall off the end.
 *
 * What it does not hold is as deliberate as what it does. No message bodies, no
 * screen contents, no page text — only the action, what it was aimed at, and how it
 * came out. A complete transcript of everything Lain ever read on screen would be a
 * far worse thing to have on a phone than the accountability is worth.
 */
class ActionLog(context: Context) {

    companion object {
        const val MAX_ENTRIES = 300

        /** How many the screen shows. Beyond this nobody is reading, they're searching. */
        const val SHOWN = 200

        private const val MAX_FIELD_CHARS = 160

        /**
         * Actions not worth a line.
         *
         * Reading something is not doing something, and a log where every third entry
         * is "read the screen" buries the entry that matters. The test is whether a
         * user would care that it happened: changing a setting yes, looking at one no.
         */
        private val UNLOGGED = setOf(
            "read_screen", "look_at_screen", "current_app", "device_status",
            "recall", "list_notes", "list_files", "read_file", "lookup_contact",
            "list_scheduled_tasks", "wait", "fetch_page"
        )
    }

    private val dao by lazy { LainDatabase.get(context).actionLog() }

    /** Newest first, for a screen that stays open. */
    fun observe(): Flow<List<ActionLogEntity>> = dao.observeRecent(SHOWN)

    /**
     * Newest first, read once.
     *
     * Memoria reads its other two lists on demand rather than subscribing, because
     * the screen is opened deliberately and briefly and a live subscription would run
     * for the whole session to keep a page nobody is looking at current. This matches
     * that, so all three sections behave the same way.
     */
    suspend fun recent(): List<ActionLogEntity> = withContext(Dispatchers.IO) {
        runCatching { dao.recent(SHOWN) }.getOrDefault(emptyList())
    }

    /**
     * @param goal the request this was part of, so the entry says why.
     */
    suspend fun record(
        action: String,
        detail: String,
        succeeded: Boolean,
        outcome: String,
        goal: String
    ) = withContext(Dispatchers.IO) {
        if (action in UNLOGGED) return@withContext
        runCatching {
            dao.insert(
                ActionLogEntity(
                    action = action,
                    detail = detail.trim().take(MAX_FIELD_CHARS),
                    succeeded = succeeded,
                    outcome = outcome.trim().take(MAX_FIELD_CHARS),
                    goal = goal.trim().take(MAX_FIELD_CHARS)
                )
            )
            // Cheap enough to check every write, and it keeps the table from ever
            // needing a maintenance pass that might not run.
            if (dao.count() > MAX_ENTRIES) dao.prune(MAX_ENTRIES)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
}

/**
 * What the current turn is for, so an action can say why it happened.
 *
 * A one-slot handover rather than a parameter threaded through the tool loop and
 * every dispatch branch: turns run one at a time, the goal is set when the turn
 * starts, and every action logged before the next turn belongs to it. The same
 * shape as [com.lain.assistant.agent.SpokenSegments], for the same reason.
 */
object CurrentGoal {

    @Volatile
    private var goal: String = ""

    fun set(userMessage: String) {
        goal = userMessage.trim()
    }

    fun get(): String = goal
}
