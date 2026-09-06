package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two targeted fixes from the WAITING-data/DEADLINE-confidence follow-up round:
 * (1) WAITING's rule-engine coverage broadened to subject-agnostic future-commitment patterns
 * (formal/casual/third-person/verb-overlap — see NotificationClassifier's futureCommitmentPattern
 * and the new WAITING_PATTERNS entries) and (2) DEADLINE confidence recalibration (midnight/noon
 * date recognition, "end of the month" phrasing, ordinal dates, and the "final call" idiom no
 * longer colliding with ACTION's "call" verb — see BY_DEADLINE_PATTERN/DEADLINE_COMBO_DATE_PATTERNS/
 * ACTION_VERB_SUPPRESSED_BY). The bulk of the new WAITING training data itself lives in
 * DiverseNotificationCorpus (48 hand-written examples across four subtypes); these are a
 * representative sample proving the underlying mechanism, not exhaustive re-coverage of all 48.
 */
class WaitingDiversityAndDeadlineConfidenceTest {

    private fun classify(sourceApp: String, sender: String, text: String) =
        NotificationClassifier.classify(sourceApp, sender, TextNormalizer.normalize(text))

    // ---- WAITING: subject-agnostic future-commitment (formal + third-person) ----

    @Test
    fun `third-person future commitments on verb-overlap verbs read as WAITING`() {
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "They said they will call back after lunch.").state)
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "The vendor confirmed they will send it out this week.").state)
        assertEquals(ClassifiedState.WAITING, classify("com.google.android.gm", "Manager", "The team will confirm shortly.").state)
    }

    @Test
    fun `casual regional future-commitment prefixes suppress the ACTION verb`() {
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "bout to send it").state)
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "fixing to call you back in a sec").state)
    }

    @Test
    fun `formal will-have and should-have commitments read as WAITING`() {
        assertEquals(ClassifiedState.WAITING, classify("com.google.android.gm", "Manager", "I'll have the presentation to you shortly.").state)
        assertEquals(ClassifiedState.WAITING, classify("com.google.android.gm", "Support", "Should have an update within the hour.").state)
    }

    @Test
    fun `give-me-a-moment style commitments read as WAITING, not ACTION via the bare check verb`() {
        val result = classify("com.whatsapp", "Sam", "Give me a moment to check on that.")
        assertEquals(ClassifiedState.WAITING, result.state)
    }

    @Test
    fun `progress-status idioms read as WAITING`() {
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "One moment while I sort this out.").state)
        assertEquals(ClassifiedState.WAITING, classify("com.google.android.gm", "Support", "Working through it, appreciate your patience.").state)
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "almost done, hang tight").state)
        assertEquals(ClassifiedState.WAITING, classify("com.google.android.gm", "Support", "Please allow 24 to 48 hours while we look into this.").state)
    }

    @Test
    fun `on-my-way family covers third-person variants too`() {
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "The plumber said he's on his way.").state)
        assertEquals(ClassifiedState.WAITING, classify("com.whatsapp", "Sam", "on the way with the docs").state)
    }

    // ---- DEADLINE: confidence recalibration ----

    @Test
    fun `final call idiom no longer collides with ACTION's call verb`() {
        val result = classify("com.google.android.gm", "Vendor", "Final call — offer expires at midnight.")
        assertEquals(ClassifiedState.DEADLINE, result.state)
        assertTrue("expected strong confidence, got ${result.confidence}", result.confidence >= 70)
    }

    @Test
    fun `midnight and noon are recognized as deadline time expressions`() {
        val midnight = classify("com.google.android.gm", "Registrar", "Assignment due at 11:59pm tonight.")
        assertEquals(ClassifiedState.DEADLINE, midnight.state)
        assertTrue(midnight.confidence >= 70)

        val noon = classify("com.google.android.gm", "Registrar", "Tickets must be claimed before noon.")
        assertEquals(ClassifiedState.DEADLINE, noon.state)
    }

    @Test
    fun `end of the month phrasing is recognized the same as end of month`() {
        val result = classify("com.google.android.gm", "Bank Alert", "Payment due by the end of the month.")
        assertEquals(ClassifiedState.DEADLINE, result.state)
        assertTrue("expected strong confidence, got ${result.confidence}", result.confidence >= 70)
    }

    @Test
    fun `ordinal dates strengthen an already-established deadline`() {
        val result = classify("com.google.android.gm", "Registrar", "Rent due by the 1st.")
        assertEquals(ClassifiedState.DEADLINE, result.state)
        assertTrue("expected strong confidence, got ${result.confidence}", result.confidence >= 70)
    }

    @Test
    fun `previously-fixed clear-cut deadlines remain strong confidence`() {
        assertTrue(classify("com.examplebank.mobile", "Bank Alert", "payment due sep 12").confidence >= 70)
        assertTrue(classify("com.google.android.gm", "Registrar", "cutoff is 5pm today").confidence >= 70)
    }
}
