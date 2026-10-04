package com.futurepath.actionbox.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEntity::class, LearningPatternEntity::class, VipSenderEntity::class],
    version = 17,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    abstract fun learningPatternDao(): LearningPatternDao

    abstract fun vipSenderDao(): VipSenderDao

    companion object {
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captured_notifications ADD COLUMN pinnedAt INTEGER")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "actionbox.db"
                )
                    // 16 -> 17 is a real migration: the app is on a Play testing track now, so
                    // testers' captured notifications must survive the update. Any other
                    // (older/unknown) version still falls back to a rebuild.
                    .addMigrations(MIGRATION_16_17)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
