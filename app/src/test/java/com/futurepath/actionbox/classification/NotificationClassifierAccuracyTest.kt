package com.futurepath.actionbox.classification

import org.junit.Test
import java.io.File

/**
 * Not a correctness gate — a scored report against a hand-labeled test set, covering all six
 * categories in both formal and casual/slang phrasing, run against the real classifier and
 * normalizer (exactly as NotificationRepository.capture() invokes them). Writes a plain-text
 * report to build/classifier-accuracy-report.txt since Gradle suppresses test stdout by default.
 */
class NotificationClassifierAccuracyTest {

    data class Case(
        val sourceApp: String,
        val sender: String,
        val text: String,
        val expected: ClassifiedState
    )

    private val cases = listOf(
        // ---------------- ACTION (10) ----------------
        Case("com.whatsapp", "Priya", "Can you send me the quarterly report by end of day?", ClassifiedState.ACTION),
        Case("com.whatsapp", "Sam", "pls send me the file rn", ClassifiedState.ACTION),
        Case("com.whatsapp", "Legal Team", "Could you review the attached contract and confirm receipt?", ClassifiedState.ACTION),
        Case("com.whatsapp", "Mom", "can u pick up milk on ur way home", ClassifiedState.ACTION),
        Case("com.whatsapp", "HR", "Please submit your timesheet before Friday.", ClassifiedState.ACTION),
        Case("com.whatsapp", "Jordan", "Please sign and forward this document to legal.", ClassifiedState.ACTION),
        Case("com.whatsapp", "Alex", "yo can u call the landlord asap", ClassifiedState.ACTION),
        Case("com.whatsapp", "Manager", "Need you to complete the onboarding form today.", ClassifiedState.ACTION),
        Case("com.whatsapp", "Chris", "bring ur laptop 2moro plz", ClassifiedState.ACTION),
        Case("com.whatsapp", "Dana", "Could you buy some coffee on the way in?", ClassifiedState.ACTION),

        // ---------------- REPLY (10) ----------------
        Case("com.whatsapp", "Taylor", "Let me know if this time works for you.", ClassifiedState.REPLY),
        Case("com.whatsapp", "Jamie", "lmk if ur free later", ClassifiedState.REPLY),
        Case("com.whatsapp", "Recruiter", "Please get back to me at your earliest convenience.", ClassifiedState.REPLY),
        Case("com.whatsapp", "Robin", "hmu when ur done", ClassifiedState.REPLY),
        Case("com.whatsapp", "Morgan", "Tell me what you think about the proposal.", ClassifiedState.REPLY),
        Case("com.whatsapp", "Casey", "call me when u get a sec", ClassifiedState.REPLY),
        Case("com.whatsapp", "Client", "Are you free tomorrow afternoon for a quick call?", ClassifiedState.REPLY),
        Case("com.whatsapp", "Riley", "text me when u land", ClassifiedState.REPLY),
        Case("com.whatsapp", "Vendor", "Can you confirm you received this?", ClassifiedState.REPLY),
        Case("com.whatsapp", "Quinn", "keep me posted on how it goes", ClassifiedState.REPLY),

        // ---------------- WAITING (10) ----------------
        Case("com.whatsapp", "Priya", "I will send the invoice shortly.", ClassifiedState.WAITING),
        Case("com.whatsapp", "Sam", "ill get back to u in a bit", ClassifiedState.WAITING),
        Case("com.whatsapp", "Alex", "I'll check the schedule and confirm.", ClassifiedState.WAITING),
        Case("com.whatsapp", "Chris", "on it, gimme a sec", ClassifiedState.WAITING),
        Case("com.whatsapp", "Support", "We're working on it and will update you soon.", ClassifiedState.WAITING),
        Case("com.whatsapp", "Dana", "ill bring it tmrw", ClassifiedState.WAITING),
        Case("com.whatsapp", "Shipping", "You should expect it by tomorrow morning.", ClassifiedState.WAITING),
        Case("com.whatsapp", "Jamie", "will do!", ClassifiedState.WAITING),
        Case("com.whatsapp", "Morgan", "I'll get back to you shortly.", ClassifiedState.WAITING),
        Case("com.whatsapp", "Robin", "ill reply in a min", ClassifiedState.WAITING),

        // ---------------- DEADLINE (10) ----------------
        Case("com.whatsapp", "Landlord", "Your rent is due on Friday.", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Roommate", "rent due tmr dont forget", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Manager", "The proposal is due by end of week.", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Classmate", "assignment deadline is monday", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "DMV", "Final date to renew your license is next week.", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Ops", "gotta submit b4 tuesday or ur late", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Landlord", "Payment is due before the end of the month.", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Registrar", "last day to apply is sunday", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Utility Co", "The offer expires today at midnight.", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "Events Team", "cutoff for registration is this friday", ClassifiedState.DEADLINE),

        // ---------------- FYI (10) ----------------
        Case("com.whatsapp", "Amazon", "Your order has been delivered.", ClassifiedState.FYI),
        Case("com.whatsapp", "Courier", "ur package arrived just now", ClassifiedState.FYI),
        Case("com.whatsapp", "Billing", "Payment received, thank you.", ClassifiedState.FYI),
        Case("com.whatsapp", "Shop", "order confirmed, ty!", ClassifiedState.FYI),
        Case("com.whatsapp", "Airline", "Your flight has landed in Chicago.", ClassifiedState.FYI),
        Case("com.whatsapp", "IT Dept", "backup completed successfully", ClassifiedState.FYI),
        Case("com.whatsapp", "Bank", "Transaction completed successfully.", ClassifiedState.FYI),
        Case("com.whatsapp", "Coworker", "meeting moved to 3pm", ClassifiedState.FYI),
        Case("com.whatsapp", "Bank", "Your verification code is 482911.", ClassifiedState.FYI),
        Case("com.whatsapp", "Salon", "booking confirmed for sat", ClassifiedState.FYI),

        // ---------------- NOISE (10) ----------------
        Case("com.google.android.youtube", "YouTube", "You have a new video from TechChannel", ClassifiedState.NOISE),
        Case("com.instagram.android", "Instagram", "Someone liked your photo", ClassifiedState.NOISE),
        Case("com.instagram.android", "Instagram", "Check out this recommended for you post", ClassifiedState.NOISE),
        Case("com.twitter.android", "Twitter", "Flash sale - 50% off today only!", ClassifiedState.NOISE),
        Case("com.instagram.android", "Instagram", "You have a new follower", ClassifiedState.NOISE),
        Case("com.some.game.app", "GameApp", "Claim your daily reward now!", ClassifiedState.NOISE),
        Case("com.twitter.android", "Twitter", "New post from @someuser is trending", ClassifiedState.NOISE),
        Case("com.facebook.katana", "Facebook", "Someone commented on your post", ClassifiedState.NOISE),
        Case("com.pinterest", "Pinterest", "Your suggested post of the week", ClassifiedState.NOISE),
        Case("com.zhiliaoapp.musically", "TikTok", "Limited time promotion just for you!", ClassifiedState.NOISE)
    )

    @Test
    fun runAccuracyReport() {
        val sb = StringBuilder()
        var correct = 0
        val misses = mutableListOf<String>()
        val byCategory = linkedMapOf<ClassifiedState, MutableList<Boolean>>()

        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            val result = NotificationClassifier.classify(case.sourceApp, case.sender, normalized)
            val isCorrect = result.state == case.expected
            if (isCorrect) correct++
            byCategory.getOrPut(case.expected) { mutableListOf() }.add(isCorrect)
            if (!isCorrect) {
                misses += "  MISS [expected ${case.expected}]: \"${case.text}\" -> got ${result.state} " +
                    "(confidence=${result.confidence}%, summary=${result.summary})"
            }
        }

        sb.appendLine("=== ActionBox NotificationClassifier accuracy report ===")
        sb.appendLine("Total cases: ${cases.size}")
        sb.appendLine("Correct: $correct")
        sb.appendLine("Accuracy: ${"%.1f".format(100.0 * correct / cases.size)}%")
        sb.appendLine()
        sb.appendLine("-- By category --")
        for ((category, outcomes) in byCategory) {
            val catCorrect = outcomes.count { it }
            sb.appendLine("$category: $catCorrect/${outcomes.size}")
        }
        sb.appendLine()
        sb.appendLine("-- Failures --")
        if (misses.isEmpty()) sb.appendLine("  (none)") else misses.forEach { sb.appendLine(it) }

        val report = sb.toString()
        println(report)
        File("build/classifier-accuracy-report.txt").apply {
            parentFile?.mkdirs()
            writeText(report)
        }
    }
}
