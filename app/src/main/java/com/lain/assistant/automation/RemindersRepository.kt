package com.lain.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lain.assistant.data.Reminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.remindersStore by preferencesDataStore(name = "lain_reminders")
private val REMINDERS_KEY = stringPreferencesKey("reminders_json")
private val json = Json { ignoreUnknownKeys = true }

class RemindersRepository(private val context: Context) {

    val reminders: Flow<List<Reminder>> = context.remindersStore.data.map { prefs ->
        prefs[REMINDERS_KEY]?.let { runCatching { json.decodeFromString<List<Reminder>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun schedule(text: String, triggerAtMillis: Long): AutomationResult {
        val reminder = Reminder(text = text, triggerAtMillis = triggerAtMillis)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = pendingIntentFor(reminder)
        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            save(reminders.first() + reminder)
            AutomationResult.Success("Reminder set: \"$text\"")
        } catch (t: SecurityException) {
            AutomationResult.MissingPermission(android.Manifest.permission.SCHEDULE_EXACT_ALARM)
        }
    }

    suspend fun cancel(id: String) {
        val reminder = reminders.first().firstOrNull { it.id == id } ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(pendingIntentFor(reminder))
        save(reminders.first().filterNot { it.id == id })
    }

    private fun pendingIntentFor(reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun save(list: List<Reminder>) {
        context.remindersStore.edit { it[REMINDERS_KEY] = json.encodeToString(list) }
    }
}
