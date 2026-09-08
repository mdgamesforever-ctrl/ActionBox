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
    private val tfliteClassifier = TfliteNotificationClassifier(context.applicationContext)
    private val settingsRepository = SettingsRepository.getInstance(context)

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

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
            dao.updateClassification(
                id = result.insertedRowId,
                state = classification.state,
                summary = classification.summary,
                date = classification.date,
                confidence = classification.confidence
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
     */
    private suspend fun learningBoostsFor(sourceApp: String, sender: String, normalizedText: String): Map<ClassifiedState, Int> {
        if (!settingsRepository.correctionLearningEnabled.first()) return emptyMap()

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
