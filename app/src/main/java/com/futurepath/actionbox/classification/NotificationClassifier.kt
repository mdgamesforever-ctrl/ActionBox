package com.futurepath.actionbox.classification

/** Result of classifying one captured notification. */
data class ClassificationResult(
    val state: ClassifiedState,
    val summary: String?,
    val date: String?
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
        if (topScore <= 0) {
            // No signal fired for anything — REPLY is the safe default for an ambiguous
            // direct message, not whichever category happens to be listed first.
            return ClassificationResult(ClassifiedState.REPLY, summary = null, date = extractDate(text))
        }

        // Among categories tied for the top score, prefer the more actionable/urgent one —
        // a message that's plausibly both a deadline and an FYI is more useful surfaced as
        // a deadline. NOISE is checked first since a strong app-level signal should win
        // outright rather than lose a tie to a coincidental keyword elsewhere.
        val winner = TIE_BREAK_ORDER.first { scores[it] == topScore }

        val date = extractDate(text)
        val summary = when (winner) {
            ClassifiedState.ACTION, ClassifiedState.DEADLINE, ClassifiedState.WAITING ->
                extractSummary(text, sender)
            else -> null
        }
        return ClassificationResult(winner, summary, date)
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
