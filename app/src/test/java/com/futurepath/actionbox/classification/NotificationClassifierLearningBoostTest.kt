package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only the `learningBoosts` parameter added to [NotificationClassifier.classify] for
 * the local learning layer (see NotificationRepository.learningBoostsFor) — the pure,
 * text-in/result-out half of that feature. The Room-backed half (accumulating corrections
 * into LearningPatternEntity and deciding when a pattern is "strong") lives in
 * LearningPatternDao and needs a real device/emulator to exercise, unlike this classifier
 * package which has zero Android dependencies and runs on the local JVM.
 */
class NotificationClassifierLearningBoostTest {

    @Test
    fun `no boosts behaves exactly as before`() {
        val text = "hey"
        val withoutBoosts = NotificationClassifier.classify("com.whatsapp", "Sam", text)
        val withEmptyBoosts = NotificationClassifier.classify("com.whatsapp", "Sam", text, emptyMap())
        assertEquals(withoutBoosts, withEmptyBoosts)
    }

    @Test
    fun `a strong boost tips an otherwise weak-signal message to the learned category`() {
        // On its own this has no scoring signal for any category and falls back to FYI.
        val text = "ok"
        val baseline = NotificationClassifier.classify("com.whatsapp", "Sam", text)
        assertEquals(ClassifiedState.FYI, baseline.state)

        val boosted = NotificationClassifier.classify(
            "com.whatsapp", "Sam", text,
            mapOf(ClassifiedState.WAITING to 5)
        )
        assertEquals(ClassifiedState.WAITING, boosted.state)
    }

    @Test
    fun `a boost can overturn a weak competing signal but not swamp a strong one`() {
        // "are you free" is a REPLY signal (score 2). A modest ACTION boost should not
        // override a message carrying its own strong ACTION signal.
        val text = "Can you send me the report? Also, are you free later?"
        val result = NotificationClassifier.classify(
            "com.whatsapp", "Sam", text,
            mapOf(ClassifiedState.REPLY to 2)
        )
        assertTrue(result.state == ClassifiedState.ACTION)
    }
}
