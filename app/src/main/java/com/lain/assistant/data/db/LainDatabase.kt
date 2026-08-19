package com.lain.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LainDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao

    companion object {
        @Volatile private var instance: LainDatabase? = null

        fun get(context: Context): LainDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LainDatabase::class.java,
                    "lain.db"
                )
                    // Writes are small and frequent (one row per turn); WAL keeps them
                    // off the read path so the UI never blocks on a flush.
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
    }
}
