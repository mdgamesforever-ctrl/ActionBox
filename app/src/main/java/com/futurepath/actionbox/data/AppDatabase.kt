package com.futurepath.actionbox.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationEntity::class, NotificationDebugEvent::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    abstract fun notificationDebugEventDao(): NotificationDebugEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "actionbox.db"
                )
                    // Pre-release debug build; no captured data is worth preserving across
                    // these schema changes (unique indices added for dedup).
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
