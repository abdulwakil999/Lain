package com.lain.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        ActionLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LainDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao
    abstract fun actionLog(): ActionLogDao

    companion object {
        /**
         * Adds the action log.
         *
         * A real migration rather than a destructive one, and that is the whole
         * point of writing it: `fallbackToDestructiveMigration` would have deleted
         * every conversation and every remembered fact on upgrade, to add a table
         * that nothing else depends on. The statement only creates; it touches no
         * existing row.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `action_log` (" +
                        "`id` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "`succeeded` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`goal` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_log_at` ON `action_log` (`at`)")
            }
        }

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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
