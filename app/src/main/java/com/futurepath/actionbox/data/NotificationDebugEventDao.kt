package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDebugEventDao {

    @Insert
    suspend fun insert(event: NotificationDebugEvent): Long

    @Query("SELECT * FROM notification_debug_events ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<NotificationDebugEvent>>
}
