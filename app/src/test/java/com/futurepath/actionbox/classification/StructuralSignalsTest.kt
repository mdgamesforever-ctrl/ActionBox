package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One or more tests per structural (metadata/format-based, not word-content) classification
 * signal added on top of the rule engine: impersonal-sender detection, banking/delivery
 * template fingerprints, an unnamed-bot formatting fingerprint, and package-identity priors
 * for gaming/calendar apps and noreply@ email senders. See NotificationClassifier's
 * isImpersonalSender/isBankingTransactionTemplate/isDeliveryStatusTemplate/isBotFormattedContent
 * and the GAMING_PACKAGES/CALENDAR_PACKAGES/NOREPLY_EMAIL_PATTERN constants for the actual
 * implementations. Each test picks text that avoids existing word-based patterns wherever
 * possible, so it actually exercises the new structural signal rather than passing anyway via
 * an unrelated literal-phrase match.
 */
class StructuralSignalsTest {

    private fun classify(sourceApp: String, sender: String, text: String) =
        NotificationClassifier.classify(sourceApp, sender, TextNormalizer.normalize(text))

    // ---- 1: impersonal sender (sender IS the app/brand, not a human contact) ----

    @Test
    fun `impersonal app-brand sender scores higher NOISE than the same text from a personal contact`() {
        val text = "check out this photo"
        val brandSender = NotificationClassifier.scoreCategories("com.instagram.android", "Instagram", text)
        val personalSender = NotificationClassifier.scoreCategories("com.instagram.android", "Jordan", text)
        assertTrue(
            "brand-sender NOISE (${brandSender[ClassifiedState.NOISE]}) should exceed personal-sender NOISE (${personalSender[ClassifiedState.NOISE]})",
            (brandSender[ClassifiedState.NOISE] ?: 0) > (personalSender[ClassifiedState.NOISE] ?: 0)
        )
    }

    @Test
    fun `impersonal sender bias does not fire for a shopping app where every sender is the brand`() {
        // Amazon's sender is always "Amazon" for genuine delivery FYIs too — see
        // isImpersonalSender's doc for why this app is deliberately excluded from the brand map.
        val result = classify("com.amazon.mShop.android.shopping", "Amazon", "Your package arrived at noon.")
        assertEquals(ClassifiedState.FYI, result.state)
    }

    // ---- 2: banking transaction template (masked account + currency + balance keyword) ----

    @Test
    fun `masked account plus currency plus balance reads as FYI with real confidence, no due language`() {
        val result = classify(
            "com.google.android.gm", "Bank Alert",
            "$500.00 debited from A/c ****6791. Avail Bal: $2,300.00"
        )
        assertEquals(ClassifiedState.FYI, result.state)
        assertTrue("expected real confidence, got ${result.confidence}", result.confidence >= 40)
    }

    @Test
    fun `masked account plus currency plus balance reads as DEADLINE when due language is present`() {
        val result = classify(
            "com.google.android.gm", "Bank Alert",
            "Rs.5,000 due from A/c XXXX6791. Avail Bal: Rs.500."
        )
        assertEquals(ClassifiedState.DEADLINE, result.state)
    }

    // ---- 3: delivery/shipping template (order#/brand-sender/status-verb, 2 of 3) ----

    @Test
    fun `order number plus brand sender plus shipped verb reads as FYI without the exact phrase`() {
        val result = classify("com.google.android.gm", "FedEx", "Order #48213 shipped via FedEx.")
        assertEquals(ClassifiedState.FYI, result.state)
        assertTrue("expected real confidence, got ${result.confidence}", result.confidence >= 40)
    }

    @Test
    fun `brand sender plus delivered verb reads as FYI even with no order number`() {
        val result = classify("com.google.android.gm", "UPS", "Delivered by UPS.")
        assertEquals(ClassifiedState.FYI, result.state)
    }

    // ---- 4: unnamed-bot formatting fingerprint (mentions + emoji header + stat pairs) ----

    @Test
    fun `an unnamed sender with bot-shaped formatting reads as NOISE despite no bot name or mass mention`() {
        val result = classify(
            "com.discord", "Server Announcements",
            "🎉 GIVEAWAY 🎉\n@alice @bob you're entered!\nEntries: 152 | Time Left: 2h"
        )
        assertEquals(ClassifiedState.NOISE, result.state)
    }

    @Test
    fun `a single mention with no emoji or stat pairs does not trigger the bot-format fingerprint`() {
        val botFormatted = NotificationClassifier.scoreCategories(
            "com.discord", "Server Announcements",
            "🎉 GIVEAWAY 🎉\n@alice @bob you're entered!\nEntries: 152 | Time Left: 2h"
        )
        val ordinaryMessage = NotificationClassifier.scoreCategories(
            "com.discord", "Sam", "hey @alice check this out"
        )
        assertTrue(
            (botFormatted[ClassifiedState.NOISE] ?: 0) > (ordinaryMessage[ClassifiedState.NOISE] ?: 0)
        )
    }

    // ---- 5: gaming-package NOISE prior ----

    @Test
    fun `a known gaming package biases hard toward NOISE even without a matching NOISE phrase`() {
        val result = classify("com.king.candycrushsaga", "Candy Crush Saga", "Event ends in 3 hours!")
        assertEquals(ClassifiedState.NOISE, result.state)
        assertTrue("expected real confidence, got ${result.confidence}", result.confidence >= 40)
    }

    // ---- 6: calendar-package DEADLINE/FYI prior ----

    @Test
    fun `calendar package identity boosts both FYI and DEADLINE over the same text from a messaging app`() {
        val text = "Team sync starting in 10 minutes."
        val fromCalendar = NotificationClassifier.scoreCategories("com.google.android.calendar", "Calendar", text)
        val fromMessaging = NotificationClassifier.scoreCategories("com.whatsapp", "Calendar", text)
        assertTrue((fromCalendar[ClassifiedState.FYI] ?: 0) > (fromMessaging[ClassifiedState.FYI] ?: 0))
        assertTrue((fromCalendar[ClassifiedState.DEADLINE] ?: 0) > (fromMessaging[ClassifiedState.DEADLINE] ?: 0))
    }

    @Test
    fun `a calendar reschedule notice still reads as FYI with the package prior added`() {
        val result = classify("com.google.android.calendar", "Calendar", "Meeting rescheduled to 3pm.")
        assertEquals(ClassifiedState.FYI, result.state)
    }

    // ---- 7: noreply@ email sender treated as a moderate automated-sender bias ----

    @Test
    fun `a noreply sender nudges toward NOISE relative to the same text from a named person`() {
        val text = "your weekly digest is ready"
        val fromNoreply = NotificationClassifier.scoreCategories("com.google.android.gm", "noreply@example.com", text)
        val fromPerson = NotificationClassifier.scoreCategories("com.google.android.gm", "Alex", text)
        assertTrue((fromNoreply[ClassifiedState.NOISE] ?: 0) > (fromPerson[ClassifiedState.NOISE] ?: 0))
    }

    @Test
    fun `a noreply sender does not drown out a genuine banking FYI on the same message`() {
        // A bank's real transaction alerts are routinely sent from a noreply address — the
        // moderate noreply bias must not out-compete the stronger, more specific banking
        // template signal (see scoreNoise's noreplyScore doc for why they're weighted apart).
        val result = classify(
            "com.google.android.gm", "noreply@examplebank.com",
            "$500.00 debited from A/c ****6791. Avail Bal: $2,300.00"
        )
        assertEquals(ClassifiedState.FYI, result.state)
    }
}
