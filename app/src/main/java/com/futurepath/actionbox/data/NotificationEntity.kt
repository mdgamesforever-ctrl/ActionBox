package com.futurepath.actionbox.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.futurepath.actionbox.classification.ClassifiedState

@Entity(
    tableName = "captured_notifications",
    indices = [
        // NOT unique: apps like Messenger/WhatsApp reuse the same notificationKey for an
        // entire conversation thread, updating it in place for every new message rather
        // than issuing a new key per message. A unique constraint on this column alone
        // would reject a genuinely new message just for sharing a key with an older one —
        // this index exists only to make the key+text lookup in NotificationDao fast.
        Index(value = ["notificationKey"]),
        // Two captures are only the same real event if they share the exact message-level
        // timestamp (from MessagingStyle when available, not device capture time) AND
        // identical text — this is what actually distinguishes two different messages
        // arriving seconds apart from one message reported twice.
        Index(value = ["sourceApp", "sender", "text", "timestamp"], unique = true)
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val notificationKey: String,
    val sourceApp: String,
    val sender: String,
    val text: String,
    // TextNormalizer's output for [text] — lowercased, de-stretched, slang-expanded.
    // Classification runs against this, not the raw text; [text] is kept as-is for display.
    val normalizedText: String,
    val timestamp: Long,
    // Device wall-clock time this row was inserted — distinct from [timestamp], which is
    // the message's own timestamp. Lets a later duplicate lookup show whether it collided
    // with something captured a second ago (a genuine repeat callback) or hours/days ago
    // (stale data from an earlier test run), instead of just "it was a duplicate."
    val capturedAt: Long,
    val isProcessed: Boolean = false,
    // Set by NotificationClassifier shortly after capture; null until then.
    val classifiedState: ClassifiedState? = null,
    val extractedSummary: String? = null,
    val extractedDate: String? = null,
    // 0-100, set alongside classifiedState. See NotificationClassifier.computeConfidence.
    val confidenceScore: Int? = null
)
