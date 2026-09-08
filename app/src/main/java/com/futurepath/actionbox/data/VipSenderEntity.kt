package com.futurepath.actionbox.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A sender or app the user has flagged as VIP (Pro feature — see
 * [NotificationRepository.capture]'s VIP-escalation check). A blank [sender] means the whole
 * [sourceApp] is VIP (e.g. "treat every WhatsApp notification as VIP"); a non-blank [sender]
 * scopes it to just that sender within that app.
 */
@Entity(tableName = "vip_senders")
data class VipSenderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceApp: String,
    val sender: String,
    val createdAt: Long
)
