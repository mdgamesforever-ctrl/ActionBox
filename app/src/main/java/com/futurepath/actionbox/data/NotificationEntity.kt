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
    val confidenceScore: Int? = null,
    // User-supplied override from the feed's category picker. classifiedState is left
    // untouched so the classifier's original pick and the user's correction can be
    // compared later (e.g. to measure real-world accuracy or retrain heuristics).
    val correctedState: ClassifiedState? = null,
    // Set by TfliteNotificationClassifier alongside classifiedState, when the on-device ML
    // model loaded successfully (see that class's doc for the model and its held-out
    // accuracy) — recorded purely for comparison against classifiedState/correctedState, not
    // shown in the UI or used for any decision yet. Null if ML classification is unavailable
    // or failed for this notification.
    val mlClassifiedState: ClassifiedState? = null,
    val mlConfidence: Int? = null,
    // Set once a WAITING follow-up nudge has been sent for this item (see
    // com.futurepath.actionbox.reminders.WaitingNudgeWorker) so it's never nudged twice — null
    // until then, and left untouched if the item is later corrected away from WAITING.
    val waitingNudgedAt: Long? = null,
    // Swipe-right in the grouped inbox (see ui/components/SwipeableNotificationCard.kt) sets
    // this to the device time it happened; null means still active. Kept rather than deleting
    // the row so it still counts toward history/retention like any other captured notification.
    val handledAt: Long? = null,
    // Swipe-left + a duration pick (see reminders/SnoozeCalculator.kt) sets this to the epoch
    // millis the item should reappear; com.futurepath.actionbox.reminders.SnoozeWorker clears it
    // back to null once that time passes, which is what makes the item reappear — no "now"
    // comparison needed anywhere else.
    val snoozedUntil: Long? = null
)

/**
 * The category actually shown to the user for [this] notification — their correction if they've
 * made one, otherwise the classifier's own pick. Shared by the grouped inbox screens
 * (NotificationViewModel.itemsByCategory) and the reminder workers so both agree on what's
 * currently "in" each category.
 */
val NotificationEntity.effectiveState: ClassifiedState?
    get() = correctedState ?: classifiedState

/**
 * Groups only the notifications that currently belong in the active inbox — handled ones swiped
 * away and snoozed ones not yet due are both excluded — by [effectiveState]. Shared by
 * [com.futurepath.actionbox.viewmodel.NotificationViewModel.itemsByCategory] so every grouped
 * inbox tab (ui/inbox) automatically reflects both swipe gestures without each tab needing its
 * own filter.
 */
fun List<NotificationEntity>.groupActiveByCategory(): Map<ClassifiedState, List<NotificationEntity>> =
    filter { it.handledAt == null && it.snoozedUntil == null }
        .groupBy { it.effectiveState ?: ClassifiedState.FYI }
