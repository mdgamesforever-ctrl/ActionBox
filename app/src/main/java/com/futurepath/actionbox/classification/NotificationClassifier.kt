package com.futurepath.actionbox.classification

/** Result of classifying one captured notification. */
data class ClassificationResult(
    val state: ClassifiedState,
    val summary: String?,
    val date: String?,
    /** 0-100. See [NotificationClassifier.computeConfidence] for how it's derived. */
    val confidence: Int
)

/**
 * Rule-based (not ML) classification for v1. Each category scores independently against
 * its own pattern group(s), and the highest-scoring category wins — rather than the first
 * matching keyword short-circuiting the rest — so a notification carrying signals for
 * multiple categories is decided by which is actually strongest, not by check order.
 * Accuracy tuning and per-app parsing are later work.
 */
object NotificationClassifier {

    fun classify(sourceApp: String, sender: String, text: String): ClassificationResult {
        val lowerText = text.lowercase()

        val scores = mapOf(
            ClassifiedState.NOISE to scoreNoise(sourceApp, lowerText),
            ClassifiedState.FYI to score(lowerText, FYI_PATTERNS),
            ClassifiedState.DEADLINE to scoreDeadline(lowerText),
            ClassifiedState.ACTION to scoreAction(lowerText),
            ClassifiedState.WAITING to score(lowerText, WAITING_PATTERNS),
            ClassifiedState.REPLY to score(lowerText, REPLY_PATTERNS)
        )

        val topScore = scores.values.max()
        // Among categories tied for the top score, prefer the more actionable/urgent one —
        // a message that's plausibly both a deadline and an FYI is more useful surfaced as
        // a deadline. NOISE is checked first since a strong app-level signal should win
        // outright rather than lose a tie to a coincidental keyword elsewhere. When every
        // category scores 0, this arbitrarily lands on NOISE, but confidence will be 0 too
        // (below the LOW threshold), so the fallback below always overrides it to FYI.
        val winner = TIE_BREAK_ORDER.first { scores[it] == topScore }
        val runnerUpScore = scores.filterKeys { it != winner }.values.maxOrNull() ?: 0
        val confidence = computeConfidence(topScore, runnerUpScore)

        val date = extractDate(text)
        val summary = when (winner) {
            ClassifiedState.ACTION, ClassifiedState.DEADLINE, ClassifiedState.WAITING ->
                extractSummary(text, sender)
            else -> null
        }

        // Below the LOW threshold, don't trust the winning category at all — fall back to
        // FYI as the safe default. The confidence score itself is kept as computed (still
        // <55) so the UI can mark this row as needing review, and the summary/date already
        // extracted from the (uncertain) winner are still surfaced as a hint.
        val finalState = if (confidence < CONFIDENCE_LOW_THRESHOLD) ClassifiedState.FYI else winner
        return ClassificationResult(finalState, summary, date, confidence)
    }

    /**
     * Confidence blends two things: the margin by which the winner beat the runner-up
     * (0 = a dead tie, 1 = the runner-up scored nothing at all) and the winner's absolute
     * score relative to [STRENGTH_CAP] (a lone weak match shouldn't score as confidently as
     * a cluster of strong ones, even with zero competition). Margin is weighted higher
     * since "is this actually the right category" matters more than "how much evidence."
     */
    private fun computeConfidence(winnerScore: Int, runnerUpScore: Int): Int {
        if (winnerScore <= 0) return 0
        val marginRatio = (winnerScore - runnerUpScore).toFloat() / winnerScore
        val strengthRatio = (winnerScore.toFloat() / STRENGTH_CAP).coerceAtMost(1f)
        val raw = 100 * (MARGIN_WEIGHT * marginRatio + STRENGTH_WEIGHT * strengthRatio)
        return raw.toInt().coerceIn(0, 100)
    }

    private val TIE_BREAK_ORDER = listOf(
        ClassifiedState.NOISE,
        ClassifiedState.DEADLINE,
        ClassifiedState.ACTION,
        ClassifiedState.WAITING,
        ClassifiedState.FYI,
        ClassifiedState.REPLY
    )

    // ---- Per-category scoring ----------------------------------------------------------

    private fun scoreNoise(sourceApp: String, lowerText: String): Int {
        val packageScore = if (sourceApp in NOISE_PACKAGES) NOISE_PACKAGE_WEIGHT else 0
        return packageScore + score(lowerText, NOISE_PATTERNS)
    }

    private fun scoreDeadline(lowerText: String): Int {
        val dueMatches = DUE_PATTERNS.count { it.containsMatchIn(lowerText) }
        val dateMatches = DEADLINE_DATE_PATTERNS.count { it.containsMatchIn(lowerText) }
        var total = dueMatches * DUE_WEIGHT + dateMatches * DATE_WEIGHT
        if (dueMatches > 0 && dateMatches > 0) total += COMBO_BONUS
        return total
    }

    private fun scoreAction(lowerText: String): Int {
        var total = 0
        var verbHit = false
        for (verb in ACTION_VERBS) {
            if (verb.bare.containsMatchIn(lowerText)) {
                verbHit = true
                total += ACTION_VERB_WEIGHT
                if (verb.directed.containsMatchIn(lowerText)) {
                    total += ACTION_DIRECTED_BONUS
                }
            }
        }
        if (verbHit && REQUEST_MARKERS.any { it.containsMatchIn(lowerText) }) {
            total += ACTION_REQUEST_MARKER_BONUS
        }
        return total
    }

    private fun score(text: String, patterns: List<Regex>): Int = patterns.count { it.containsMatchIn(text) } * DEFAULT_WEIGHT

    private fun extractDate(text: String): String? = EXTRACT_DATE_PATTERN.find(text)?.value

    /**
     * Picks the sentence/clause containing the strongest signal (a verb, request marker, or
     * date match) as a rough one-line summary, capped to a readable length. Falls back to
     * sender + a truncated excerpt when no clause with a clear signal is found.
     */
    private fun extractSummary(text: String, sender: String): String {
        val clauses = text.split(Regex("[.!?\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val candidate = clauses.firstOrNull { clause ->
            val lower = clause.lowercase()
            ACTION_VERBS.any { it.bare.containsMatchIn(lower) } ||
                REQUEST_MARKERS.any { it.containsMatchIn(lower) } ||
                WAITING_PATTERNS.any { it.containsMatchIn(lower) } ||
                DUE_PATTERNS.any { it.containsMatchIn(lower) } ||
                DEADLINE_DATE_PATTERNS.any { it.containsMatchIn(lower) }
        } ?: clauses.firstOrNull()

        val base = candidate ?: (if (sender.isNotBlank()) "$sender: $text" else text)
        val trimmed = base.trim()
        return if (trimmed.length > 80) trimmed.take(77).trimEnd() + "…" else trimmed
    }

    // ---- Pattern groups -------------------------------------------------------------------

    private const val DEFAULT_WEIGHT = 2
    private const val NOISE_PACKAGE_WEIGHT = 10
    private const val DUE_WEIGHT = 2
    private const val DATE_WEIGHT = 2
    private const val COMBO_BONUS = 3
    private const val ACTION_VERB_WEIGHT = 2
    private const val ACTION_DIRECTED_BONUS = 1
    private const val ACTION_REQUEST_MARKER_BONUS = 2
    private const val STRENGTH_CAP = 8f
    private const val MARGIN_WEIGHT = 0.6f
    private const val STRENGTH_WEIGHT = 0.4f
    private const val CONFIDENCE_LOW_THRESHOLD = 55

    private fun phrases(vararg raw: String): List<Regex> = raw.map { Regex("\\b${Regex.escape(it)}\\b") }

    private val NOISE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.twitter.android",
        "com.facebook.katana", // Facebook feed app, distinct from com.facebook.orca (Messenger)
        "com.pinterest",
        "com.snapchat.android"
    )

    private val NOISE_PATTERNS = phrases(
        "new video", "recommended for you", "sale", "promotion", "trending", "suggested post",
        "new follower", "daily reward",
        "liked your", "started following you", "posted a new", "limited time", "flash sale",
        "new post from", "commented on your"
    )

    private val FYI_PATTERNS = phrases(
        "has shipped", "has been delivered", "out for delivery", "order confirmed",
        "payment received", "transaction completed", "meeting moved", "meeting rescheduled",
        "flight landed", "package arrived", "backup completed",
        "payment successful", "your receipt", "verification code", "otp", "confirmation number",
        "booking confirmed", "successfully completed"
    )

    private val DUE_PATTERNS = phrases(
        "due", "expires", "expiring", "deadline", "before", "by",
        "last day", "final date", "closing date", "cutoff", "renewal", "ends on"
    )

    // Day names and relative time phrases count as deadline signals on their own, per spec,
    // even without an accompanying due/expiry word.
    private val DEADLINE_DATE_PATTERNS = phrases(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "end of month", "end of week", "next week"
    )

    private data class ActionVerb(val bare: Regex, val directed: Regex)

    private val ACTION_VERBS = listOf(
        "send", "bring", "upload", "finish", "buy", "pay", "call", "pick up", "check",
        "submit", "sign", "review", "forward", "complete", "book", "cancel", "confirm"
    ).map { verb ->
        val escaped = Regex.escape(verb)
        ActionVerb(
            bare = Regex("\\b$escaped\\b"),
            // e.g. "send this", "call me", "pick up that" — verb directed at me/this/that
            // within a couple of words.
            directed = Regex("\\b$escaped\\b(?:\\s+\\w+){0,2}\\s+(me|this|that)\\b")
        )
    }

    private val REQUEST_MARKERS = phrases("can you", "could you", "would you", "need you to")

    private val WAITING_PATTERNS = phrases(
        "i'll send", "i will", "i'll check", "i'll get back to you", "expect it",
        "should arrive", "we're working on it", "i'll bring it",
        "on it", "will do", "will send", "will get back", "will reply"
    )

    private val REPLY_PATTERNS = phrases(
        "let me know", "lmk", "tell me", "get back to me", "call me", "text me", "hmu",
        "what do you think", "can you confirm", "keep me posted", "are you free", "when are you free"
    )

    // Broader than DEADLINE_DATE_PATTERNS — used only to populate extractedDate, not scoring.
    private val EXTRACT_DATE_PATTERN = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|tonight|" +
            "end of month|end of week|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}(?::\\d{2})?\\s?(?:am|pm))\\b",
        RegexOption.IGNORE_CASE
    )
}
