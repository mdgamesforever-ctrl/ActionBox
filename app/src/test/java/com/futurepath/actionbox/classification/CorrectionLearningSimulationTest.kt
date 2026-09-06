package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * End-to-end simulation of the local learning layer using the real, pure decision logic
 * ([CorrectionLearning]) and the real classifier ([NotificationClassifier]) — the only piece
 * not exercised here is the actual SQL in LearningPatternDao, which needs a device/emulator
 * and isn't available in this sandbox. The correction tallies below are exactly the shape
 * LearningPatternDao.strongCategoryFor() hands to CorrectionLearning.strongCategory() in
 * production (see that method); this test builds the tally directly instead of going through
 * Room, to simulate "3-4 corrections already recorded" without a device.
 */
class CorrectionLearningSimulationTest {

    // A fake sender on a fake app whose messages the classifier reads as ACTION on their own
    // wording, but which the user has repeatedly told ActionBox are really just FYI notices.
    private val sourceApp = "com.fake.landlordportal"
    private val sender = "Landlord Bot"
    private val firstMessage = "Please confirm you received this month's rent receipt."
    private val secondMessage = "Please confirm you received this month's utility receipt."

    @Test
    fun `before any corrections, the classifier reads this sender's message as ACTION on its own signal`() {
        val result = NotificationClassifier.classify(sourceApp, sender, firstMessage)
        assertEquals(ClassifiedState.ACTION, result.state)
    }

    @Test
    fun `after 3 consistent corrections to FYI, a new similar message from the same sender is reclassified as FYI`() {
        // BEFORE: classify a new, similarly-worded message from this sender with no learning
        // history at all — same wording pattern as the one the user is about to correct 3
        // times, so it's still scored ACTION on its own merits.
        val before = NotificationClassifier.classify(sourceApp, sender, secondMessage)
        assertEquals(ClassifiedState.ACTION, before.state)
        assertEquals(73, before.confidence) // matches the ACTION verb+directed-object signal alone

        // Simulate the user correcting 3 prior messages from this same sender to FYI via the
        // feed's picker. Each correction is one call to
        // LearningPatternDao.recordCorrection(SENDER, "Landlord Bot", FYI) in production; the
        // tally it leaves behind is exactly this map.
        val senderCorrectionHistory = mapOf(ClassifiedState.FYI to 3)

        val dominant = CorrectionLearning.strongCategory(senderCorrectionHistory)
        assertEquals(ClassifiedState.FYI, dominant)

        val boosts = CorrectionLearning.computeBoosts(
            senderCorrections = senderCorrectionHistory,
            appCorrections = emptyMap(),
            phraseCorrections = emptyMap()
        )
        assertEquals(mapOf(ClassifiedState.FYI to CorrectionLearning.SENDER_BOOST), boosts)

        // AFTER: classify the same new message again, now with the learned boost applied —
        // exactly what NotificationRepository.capture() does for every real notification.
        val after = NotificationClassifier.classify(sourceApp, sender, secondMessage, boosts)

        assertEquals(ClassifiedState.FYI, after.state)
        assertNotEquals(before.state, after.state)
        assertEquals(32, after.confidence) // learned boost (4) narrowly overtakes ACTION's own score (3)
    }

    @Test
    fun `only 2 corrections is not yet a strong pattern and does not shift classification`() {
        val weakHistory = mapOf(ClassifiedState.FYI to 2)
        assertNull(CorrectionLearning.strongCategory(weakHistory))

        val boosts = CorrectionLearning.computeBoosts(weakHistory, emptyMap(), emptyMap())
        assertEquals(emptyMap<ClassifiedState, Int>(), boosts)

        val result = NotificationClassifier.classify(sourceApp, sender, secondMessage, boosts)
        assertEquals(ClassifiedState.ACTION, result.state)
    }

    @Test
    fun `a mixed 2-vs-2 history is not consistent enough to be trusted`() {
        val mixedHistory = mapOf(ClassifiedState.FYI to 2, ClassifiedState.REPLY to 2)
        assertNull(CorrectionLearning.strongCategory(mixedHistory))
    }
}
