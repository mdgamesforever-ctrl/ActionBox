package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VipSenderDao {

    @Query("SELECT * FROM vip_senders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VipSenderEntity>>

    @Insert
    suspend fun insert(entity: VipSenderEntity): Long

    @Delete
    suspend fun delete(entity: VipSenderEntity)

    /**
     * True if ([sourceApp], [sender]) matches a stored VIP entry exactly, or matches an
     * app-wide entry (a stored row for [sourceApp] with a blank [VipSenderEntity.sender]) — see
     * [NotificationRepository.capture]'s use of this at capture time.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM vip_senders
            WHERE sourceApp = :sourceApp AND (sender = :sender OR sender = '')
        )
        """
    )
    suspend fun isVip(sourceApp: String, sender: String): Boolean
}
