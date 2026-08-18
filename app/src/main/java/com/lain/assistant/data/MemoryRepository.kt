package com.lain.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.memoryStore by preferencesDataStore(name = "lain_memory")
private val MEMORY_KEY = stringPreferencesKey("memory_json")
private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class MemoryEntry(
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Long-term facts Lain keeps about the user: what they call people ("mum" ->
 * a specific contact), which app they mean by a nickname, standing
 * preferences. Kept deliberately small and flat — every entry is injected
 * into the system prompt on each turn, so this is a budget, not a database.
 */
class MemoryRepository(private val context: Context) {

    companion object {
        /** Hard cap so memory can never quietly eat the context window. */
        const val MAX_ENTRIES = 60
        const val MAX_VALUE_CHARS = 160
    }

    val memories: Flow<List<MemoryEntry>> = context.memoryStore.data.map { prefs ->
        prefs[MEMORY_KEY]?.let { runCatching { json.decodeFromString<List<MemoryEntry>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun remember(key: String, value: String): MemoryEntry {
        val entry = MemoryEntry(
            key = key.trim().lowercase(),
            value = value.trim().take(MAX_VALUE_CHARS)
        )
        val current = memories.first().filterNot { it.key == entry.key }
        // Oldest-out when full, so the most recently useful facts survive.
        val next = (current + entry).sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)
        save(next)
        return entry
    }

    suspend fun forget(key: String): Boolean {
        val normalized = key.trim().lowercase()
        val current = memories.first()
        val next = current.filterNot { it.key == normalized }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    /** Compact block for the system prompt. Empty string when there's nothing worth saying. */
    suspend fun asPromptBlock(): String {
        val all = memories.first()
        if (all.isEmpty()) return ""
        return all.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }

    private suspend fun save(list: List<MemoryEntry>) {
        context.memoryStore.edit { it[MEMORY_KEY] = json.encodeToString(list) }
    }
}
