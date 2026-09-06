package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per issue real-device testing surfaced (WhatsApp, Messenger, and organic
 * notifications) beyond what the synthetic corpus caught. The corpus itself doesn't contain
 * most of this exact phrasing, so these are the only regression coverage for these specific
 * fixes — see NotificationClassifier's scoreDeadline/scoreCategories/ACTION_VERB_SUPPRESSED_BY
 * for the actual fixes. Filtering (issue 13) is covered separately in
 * NotificationNoiseFilterTest since it's a different layer.
 */
class RealDeviceRegressionTest {

    private fun classify(sourceApp: String, sender: String, text: String) =
        NotificationClassifier.classify(sourceApp, sender, TextNormalizer.normalize(text))

    // ---- 1 & 2: future-commitment verbs (call/send) shouldn't out-compete WAITING ----

    @Test
    fun `issue 1 - I'll call you back reads as WAITING, not ACTION`() {
        val result = classify("com.whatsapp", "Sam", "I'll call you back after the meeting")
        assertEquals(ClassifiedState.WAITING, result.state)
    }

    @Test
    fun `issue 2 - gonna send the file soon reads as WAITING, not ACTION`() {
        val result = classify("com.whatsapp", "Sam", "gonna send the file soon")
        assertEquals(ClassifiedState.WAITING, result.state)
    }

    // ---- 3: bare "expect it/expect X" should fire WAITING ----

    @Test
    fun `issue 3 - expect it in your inbox shortly reads as WAITING, not FYI`() {
        val result = classify("com.google.android.gm", "Support", "expect it in your inbox shortly")
        assertEquals(ClassifiedState.WAITING, result.state)
    }

    // ---- 4: delivery-commitment "by <time>" shouldn't trigger DEADLINE ----

    @Test
    fun `issue 4 - should arrive by tonight reads as WAITING, not DEADLINE`() {
        val result = classify("com.google.android.gm", "Shipping", "should arrive by tonight")
        assertEquals(ClassifiedState.WAITING, result.state)
    }

    // ---- 5 & 6: FYI completion-status phrasing ----

    @Test
    fun `issue 5 - package delivered reads as FYI, not ACTION`() {
        val result = classify("com.amazon.mShop.android.shopping", "Amazon", "Package delivered.")
        assertEquals(ClassifiedState.FYI, result.state)
    }

    @Test
    fun `issue 6 - download and installation completion notices read as FYI`() {
        assertEquals(ClassifiedState.FYI, classify("com.android.chrome", "Chrome", "Download complete.").state)
        assertEquals(ClassifiedState.FYI, classify("com.android.chrome", "Chrome", "download finished").state)
        assertEquals(
            ClassifiedState.FYI,
            classify("com.google.android.packageinstaller", "Package Installer", "Installation complete.").state
        )
    }

    // ---- 7: casual check-in question reads as REPLY ----

    @Test
    fun `issue 7 - how was your day reads as REPLY, not ACTION`() {
        val result = classify("com.whatsapp", "Mom", "how was your day")
        assertEquals(ClassifiedState.REPLY, result.state)
    }

    // ---- 8: promotional imperative framing reads as NOISE, not ACTION ----

    @Test
    fun `issue 8 - percent-off and check-it-out promo framing reads as NOISE, not ACTION`() {
        assertEquals(ClassifiedState.NOISE, classify("com.example.shop", "DealsApp", "50% off today only").state)
        assertEquals(
            ClassifiedState.NOISE,
            classify("com.example.shop", "DealsApp", "new update available - check it out").state
        )
    }

    // ---- 9: DEADLINE confidence shouldn't be systematically low on clear-cut deadlines ----

    @Test
    fun `issue 9 - clear-cut dated deadlines score DEADLINE with strong, not weak, confidence`() {
        val abbreviatedMonth = classify("com.examplebank.mobile", "Bank Alert", "payment due sep 12")
        assertEquals(ClassifiedState.DEADLINE, abbreviatedMonth.state)
        assertTrue(
            "expected strong confidence, got ${abbreviatedMonth.confidence}",
            abbreviatedMonth.confidence >= 70
        )

        val timeOfDay = classify("com.google.android.gm", "Registrar", "cutoff is 5pm today")
        assertEquals(ClassifiedState.DEADLINE, timeOfDay.state)
        assertTrue("expected strong confidence, got ${timeOfDay.confidence}", timeOfDay.confidence >= 70)
    }

    // ---- 10: casual/reflective day-name mentions shouldn't trigger DEADLINE by default ----

    @Test
    fun `issue 10 - a reflective social post mentioning day names is not DEADLINE`() {
        val result = classify(
            "com.facebook.orca", "Friend",
            "Sunday in Amman hits different... pretending Monday doesn't exist"
        )
        assertTrue(
            "expected NOT DEADLINE, got ${result.state}",
            result.state != ClassifiedState.DEADLINE
        )
    }

    // ---- 11: public/broadcast context biases away from REPLY/ACTION ----

    @Test
    fun `issue 11 - the same REPLY-shaped question scores lower on Reddit than in a personal DM`() {
        val text = "what do you think"
        val personal = NotificationClassifier.scoreCategories("com.whatsapp", "Sam", text)
        val public = NotificationClassifier.scoreCategories("com.reddit.frontpage", "u/someuser", text)
        assertTrue(
            "public REPLY score (${public[ClassifiedState.REPLY]}) should be lower than personal (${personal[ClassifiedState.REPLY]})",
            (public[ClassifiedState.REPLY] ?: 0) < (personal[ClassifiedState.REPLY] ?: 0)
        )
    }

    @Test
    fun `issue 11 - a Discord channel-formatted sender also biases away from REPLY`() {
        val text = "what do you think"
        val personal = NotificationClassifier.scoreCategories("com.discord", "Sam", text)
        val channel = NotificationClassifier.scoreCategories("com.discord", "#general (My Server)", text)
        assertTrue(
            (channel[ClassifiedState.REPLY] ?: 0) < (personal[ClassifiedState.REPLY] ?: 0)
        )
    }

    // ---- 12: automated bot/broadcast content reads as NOISE regardless of phrasing ----

    @Test
    fun `issue 12 - a bot sender's giveaway rules text reads as NOISE despite WAITING-ish phrasing`() {
        val result = classify(
            "com.discord", "GiveawayBot",
            "I'll pick a winner shortly. Good luck everyone!"
        )
        assertEquals(ClassifiedState.NOISE, result.state)
    }

    @Test
    fun `issue 12 - a raid-timer bot dump reads as NOISE despite DEADLINE-ish phrasing`() {
        val result = classify(
            "com.discord", "RaidBot",
            "Raid starts in 5 minutes, cutoff for signup is now. @everyone"
        )
        assertEquals(ClassifiedState.NOISE, result.state)
    }

    @Test
    fun `issue 12 - a mass mention alone (no bot-named sender) also biases toward NOISE`() {
        val personal = NotificationClassifier.scoreCategories("com.discord", "Sam", "check the schedule")
        val massMention = NotificationClassifier.scoreCategories("com.discord", "Sam", "check the schedule @everyone")
        assertTrue((massMention[ClassifiedState.NOISE] ?: 0) > (personal[ClassifiedState.NOISE] ?: 0))
    }
}
