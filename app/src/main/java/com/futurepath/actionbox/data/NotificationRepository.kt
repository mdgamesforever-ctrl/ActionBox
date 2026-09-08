package com.futurepath.actionbox.data

import android.content.Context
import android.util.Log
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.classification.CorrectionLearning
import com.futurepath.actionbox.classification.HybridClassifier
import com.futurepath.actionbox.classification.TextNormalizer
import com.futurepath.actionbox.ml.TfliteNotificationClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()
    private val learningDao = AppDatabase.getInstance(context).learningPatternDao()
    private val vipSenderDao = AppDatabase.getInstance(context).vipSenderDao()
    private val tfliteClassifier = TfliteNotificationClassifier(context.applicationContext)
    private val settingsRepository = SettingsRepository.getInstance(context)

    /** Pro feature — see ui/settings/VipSendersScreen.kt. */
    fun observeVipSenders(): Flow<List<VipSenderEntity>> = vipSenderDao.observeAll()

    suspend fun addVipSender(sourceApp: String, sender: String) {
        vipSenderDao.insert(VipSenderEntity(sourceApp = sourceApp, sender = sender, createdAt = System.currentTimeMillis()))
    }

    suspend fun removeVipSender(entity: VipSenderEntity) = vipSenderDao.delete(entity)

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    /** One-shot read for the reminder workers (see com.futurepath.actionbox.reminders), which
     * run as a single background pass rather than observing a live [Flow]. */
    suspend fun getAllOnce(): List<NotificationEntity> = dao.getAll()

    /** See [NotificationEntity.waitingNudgedAt]. */
    suspend fun markWaitingNudged(id: Long, nudgedAt: Long) = dao.markWaitingNudged(id, nudgedAt)

    /** Swipe-right in the grouped inbox. [handled] false is the snackbar "Undo" action —
     * see [NotificationEntity.handledAt]. */
    suspend fun setHandled(id: Long, handled: Boolean) =
        dao.setHandledAt(id, if (handled) System.currentTimeMillis() else null)

    /** Swipe-left + a duration pick in the grouped inbox — see [NotificationEntity.snoozedUntil]
     * and [com.futurepath.actionbox.reminders.SnoozeCalculator]. */
    suspend fun snooze(id: Long, untilMs: Long) = dao.setSnoozedUntil(id, untilMs)

    /** The undo action on the snooze snackbar — clears the snooze immediately rather than
     * waiting for it to elapse naturally, restoring the item to the active inbox right away. */
    suspend fun clearSnooze(id: Long) = dao.setSnoozedUntil(id, null)

    /**
     * Called periodically by com.futurepath.actionbox.reminders.SnoozeWorker: clears
     * [NotificationEntity.snoozedUntil] on everything whose snooze has elapsed — which alone is
     * enough to make it reappear in the active inbox, since Room's Flow re-emits on the write —
     * and returns those rows so the caller can post a "snoozed item is back" notification.
     */
    suspend fun clearExpiredSnoozes(): List<NotificationEntity> {
        val expired = dao.getExpiredSnoozes(System.currentTimeMillis())
        expired.forEach { dao.setSnoozedUntil(it.id, null) }
        return expired
    }

    /**
     * Delegates the whole check-then-insert sequence to [NotificationDao.captureIfNew],
     * which runs it as a single Room transaction so concurrent calls can't race each other
     * (see that method's doc):
     *  1. Bounded-recency content match: the same real message can reach
     *     onNotificationPosted via two genuinely different StatusBarNotification postings
     *     (rich MessagingStyle vs. plain compatibility) with two different — correctly
     *     different — notificationKeys and timestamps from two different clocks, so an
     *     identical-text match within [CROSS_SOURCE_WINDOW_MS] is treated as one event.
     *  2. Same notificationKey + identical text, for a repeat outside that window. Matching
     *     key with *different* text is never treated as a duplicate on its own — apps like
     *     Messenger/WhatsApp reuse one key for an entire conversation thread, so key alone
     *     doesn't identify a specific message.
     * On a genuine new insert, classification runs immediately (still within the background
     * coroutine the caller launched) and the row is updated with its result.
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long, receivedAt: Long) {
        val normalizedText = TextNormalizer.normalize(text)
        val result = dao.captureIfNew(
            notificationKey = notificationKey,
            sourceApp = sourceApp,
            sender = sender,
            text = text,
            normalizedText = normalizedText,
            timestamp = timestamp,
            capturedAt = receivedAt,
            recentWindowMs = CROSS_SOURCE_WINDOW_MS
        )

        if (result.insertedRowId != -1L) {
            val boosts = learningBoostsFor(sourceApp, sender, normalizedText)

            // Fetched once and used both to blend into the hybrid decision below and to
            // record the ML-alone prediction for comparison, so a single notification never
            // runs the interpreter twice. Never lets a model problem (missing/corrupt asset,
            // native library failure) affect capture: classifyDistribution() already degrades
            // to null internally, and this try/catch is belt-and-suspenders against anything
            // else unexpected the ML path might throw — HybridClassifier.classify treats null
            // as "no ML signal" and falls back to the rule engine/correction-learning score
            // alone, unchanged from prior phases' behavior.
            val mlProbabilities = try {
                tfliteClassifier.classifyDistribution(normalizedText)
            } catch (e: Exception) {
                Log.w(TAG, "On-device ML classification path failed; hybrid falls back to rule engine alone", e)
                null
            }

            val classification = HybridClassifier.classify(sourceApp, sender, normalizedText, boosts, mlProbabilities)

            // VIP escalation (Pro feature — see ui/settings/VipSendersScreen.kt): a flagged
            // sender/app always lands in ACTION, overriding whatever the classifier decided.
            // Confidence is bumped to 100 for an escalated item since this is a deterministic
            // user rule, not a guess — otherwise a low classifier confidence score could still
            // show the "needs review" treatment (see ui/components/NotificationCard.kt) on an
            // item the user explicitly told the app to always surface.
            val isVip = settingsRepository.isPro.first() && vipSenderDao.isVip(sourceApp, sender)
            Log.d(TAG, "VIP check: sourceApp=$sourceApp sender=$sender -> isVip=$isVip (classifier picked ${classification.state})")
            dao.updateClassification(
                id = result.insertedRowId,
                state = if (isVip) ClassifiedState.ACTION else classification.state,
                summary = classification.summary,
                date = classification.date,
                confidence = if (isVip) 100 else classification.confidence
            )

            // The ML model's own top pick, recorded separately from the hybrid decision above
            // purely for ongoing comparison against classifiedState/correctedState — see
            // NotificationEntity.mlClassifiedState.
            mlProbabilities?.maxByOrNull { it.value }?.let { (state, probability) ->
                dao.updateMlClassification(
                    id = result.insertedRowId,
                    state = state,
                    confidence = (probability * 100).toInt().coerceIn(0, 100)
                )
            }
        }
    }

    /**
     * Local learning layer: looks up whether this sender, app, or exact message text has a
     * strong, consistent correction history (see [LearningPatternDao.strongCategoryFor]) and,
     * if so, adds points for that category before the classifier picks a winner. All three
     * sources stack additively — e.g. a sender AND a repeated phrase both pointing to the
     * same category reinforce each other rather than one overriding the other. Purely local:
     * everything this reads comes from this device's own Room database, no account or network
     * involved.
     *
     * Pro-gated ("Smart corrections" — see SettingsScreen and the paywall): requires BOTH
     * [SettingsRepository.isPro] and the user's own on/off preference, so a Free user's
     * preference is remembered and simply picks back up automatically after they upgrade,
     * rather than needing to be re-enabled.
     */
    private suspend fun learningBoostsFor(sourceApp: String, sender: String, normalizedText: String): Map<ClassifiedState, Int> {
        if (!settingsRepository.isPro.first() || !settingsRepository.correctionLearningEnabled.first()) {
            return emptyMap()
        }

        val boosts = mutableMapOf<ClassifiedState, Int>()
        learningDao.strongCategoryFor(LearningPatternType.SENDER, sender)?.let {
            boosts[it] = (boosts[it] ?: 0) + CorrectionLearning.SENDER_BOOST
        }
        learningDao.strongCategoryFor(LearningPatternType.APP, sourceApp)?.let {
            boosts[it] = (boosts[it] ?: 0) + CorrectionLearning.APP_BOOST
        }
        learningDao.strongCategoryFor(LearningPatternType.PHRASE, normalizedText)?.let {
            boosts[it] = (boosts[it] ?: 0) + CorrectionLearning.PHRASE_BOOST
        }
        return boosts
    }

    /**
     * User-supplied correction from the feed's category picker. Persists the override (see
     * [NotificationDao.updateCorrectedState]) and feeds it into the local learning layer so
     * future notifications from the same sender/app, or repeats of the same message, benefit
     * from it (see [learningBoostsFor]).
     */
    suspend fun correctClassification(id: Long, newState: ClassifiedState) {
        dao.updateCorrectedState(id, newState)
        val notification = dao.getById(id) ?: return
        learningDao.recordCorrection(LearningPatternType.SENDER, notification.sender, newState)
        learningDao.recordCorrection(LearningPatternType.APP, notification.sourceApp, newState)
        learningDao.recordCorrection(LearningPatternType.PHRASE, notification.normalizedText, newState)
    }

    /**
     * Deletes captured notifications older than [FREE_RETENTION_DAYS] for free-tier users —
     * Pro has unlimited retention, so this is a no-op there. Cheap enough (a single indexed
     * DELETE) to call on every app open rather than needing a scheduled background job; also
     * called right after a Pro->Free downgrade so the limit takes effect immediately instead
     * of waiting for the next app launch.
     */
    suspend fun enforceRetentionPolicy() {
        if (settingsRepository.isPro.first()) return
        val cutoff = System.currentTimeMillis() - FREE_RETENTION_DAYS * DAY_MS
        dao.deleteOlderThan(cutoff)
    }

    companion object {
        private const val TAG = "NotificationRepository"

        // Covers the gap between a message's own MessagingStyle timestamp and the device
        // notification post time used when a second, plain-text posting of the same event
        // has no MessagingStyle data (observed gap in testing: ~2.5s). Short enough that an
        // identical message sent again minutes/hours later is unaffected.
        private const val CROSS_SOURCE_WINDOW_MS = 10_000L

        private const val DAY_MS = 24 * 60 * 60 * 1000L
        const val FREE_RETENTION_DAYS = 14

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
