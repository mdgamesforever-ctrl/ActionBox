package com.futurepath.actionbox.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "captured_notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceApp: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val isProcessed: Boolean = false
)
