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
     * True if ([sourceApp], [sender]) matches a stored VIP entry, or matches an app-wide entry
     * (a stored row for [sourceApp] with a blank [VipSenderEntity.sender]) — see
     * [NotificationRepository.capture]'s use of this at capture time. `COLLATE NOCASE` is
     * defense-in-depth against a sender/app string whose capitalization varies slightly between
     * messages (the same contact's display name has been observed to do this on some apps); the
     * VIP entry itself is normally picked from an exact previously-captured value (see
     * ui/settings/VipSendersScreen.kt) rather than free-typed, which is what actually fixed this
     * check never matching in practice — see that screen's doc for why free text was the bug.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM vip_senders
            WHERE sourceApp = :sourceApp COLLATE NOCASE
              AND (sender = :sender COLLATE NOCASE OR sender = '')
        )
        """
    )
    suspend fun isVip(sourceApp: String, sender: String): Boolean
}
