package com.lain.assistant.automation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lain.assistant.data.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.notesStore by preferencesDataStore(name = "lain_notes")
private val NOTES_KEY = stringPreferencesKey("notes_json")
private val json = Json { ignoreUnknownKeys = true }

class NotesRepository(private val context: Context) {

    val notes: Flow<List<Note>> = context.notesStore.data.map { prefs ->
        prefs[NOTES_KEY]?.let { runCatching { json.decodeFromString<List<Note>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun addNote(text: String): Note {
        val note = Note(text = text)
        val current = notes.first()
        save(current + note)
        return note
    }

    suspend fun deleteNote(id: String) {
        save(notes.first().filterNot { it.id == id })
    }

    /**
     * Notes about the same thing as [query], newest first.
     *
     * Concept-matched rather than substring-matched, so a note saying "pick up the
     * prescription" is found by "my medication". A note is usually one line written
     * in a hurry, and the words in it are rarely the words used to look for it later.
     */
    suspend fun search(query: String, limit: Int = 4): List<Note> {
        val wanted = com.lain.assistant.data.TextIndex.concepts(query)
        if (wanted.isEmpty()) return emptyList()
        return notes.first()
            .map { note ->
                val overlap = wanted.intersect(com.lain.assistant.data.TextIndex.concepts(note.text)).size
                note to overlap + com.lain.assistant.data.TextIndex.similarity(query, note.text)
            }
            .filter { it.second >= 1.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private suspend fun save(list: List<Note>) {
        context.notesStore.edit { it[NOTES_KEY] = json.encodeToString(list) }
    }
}
