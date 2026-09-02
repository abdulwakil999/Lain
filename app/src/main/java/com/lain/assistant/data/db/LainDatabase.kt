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
        ActionLogEntity::class,
        SkillEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class LainDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao
    abstract fun actionLog(): ActionLogDao
    abstract fun skills(): SkillDao

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

        /**
         * Adds skills.
         *
         * Same shape as the one before it: create the table, touch nothing else. A
         * skill the user taught is not something to lose to a schema change, which is
         * the entire argument against ever reaching for a destructive migration in an
         * app that keeps things for people.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `skills` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`triggers` TEXT NOT NULL, " +
                        "`steps` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "`uses` INTEGER NOT NULL, " +
                        "`fullyLocal` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_name` ON `skills` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_lastUsedAt` ON `skills` (`lastUsedAt`)")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
