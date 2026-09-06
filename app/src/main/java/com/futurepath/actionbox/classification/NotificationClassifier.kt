package com.futurepath.actionbox.classification

/** Result of classifying one captured notification. */
data class ClassificationResult(
    val state: ClassifiedState,
    val summary: String?,
    val date: String?
)

/**
 * Rule-based (not ML) classification for v1. Heuristics are intentionally simple pattern
 * matches on the raw text plus the source app package — accuracy tuning and per-app parsing
 * are later work; this only needs to produce a reasonable first-pass label without crashing.
 */
object NotificationClassifier {

    fun classify(sourceApp: String, sender: String, text: String): ClassificationResult {
        val lowerText = text.lowercase()

        if (isNoise(sourceApp, lowerText)) {
            return ClassificationResult(ClassifiedState.NOISE, summary = null, date = null)
        }

        if (containsAny(lowerText, FYI_PATTERNS)) {
            return ClassificationResult(ClassifiedState.FYI, summary = null, date = extractDate(text))
        }

        val detectedDate = extractDate(text)
        val hasDueLanguage = containsAny(lowerText, DUE_PATTERNS)
        if (detectedDate != null && hasDueLanguage) {
            return ClassificationResult(ClassifiedState.DEADLINE, summary = extractSummary(text, sender), date = detectedDate)
        }

        if (containsAny(lowerText, ACTION_PATTERNS) || isDirectedQuestion(lowerText)) {
            return ClassificationResult(ClassifiedState.ACTION, summary = extractSummary(text, sender), date = detectedDate)
        }

        if (containsAny(lowerText, WAITING_PATTERNS)) {
            return ClassificationResult(ClassifiedState.WAITING, summary = extractSummary(text, sender), date = detectedDate)
        }

        return ClassificationResult(ClassifiedState.REPLY, summary = null, date = detectedDate)
    }

    private fun isNoise(sourceApp: String, lowerText: String): Boolean {
        if (sourceApp in NOISE_PACKAGES) return true
        return containsAny(lowerText, NOISE_PATTERNS)
    }

    private fun isDirectedQuestion(lowerText: String): Boolean {
        val trimmed = lowerText.trim()
        if (!trimmed.endsWith("?")) return false
        return trimmed.contains(" you ") || trimmed.contains(" you?") || trimmed.contains("your ") ||
            trimmed.startsWith("can you") || trimmed.startsWith("could you") || trimmed.startsWith("would you")
    }

    private fun containsAny(text: String, patterns: List<Regex>): Boolean = patterns.any { it.containsMatchIn(text) }

    private fun extractDate(text: String): String? = DATE_PATTERN.find(text)?.value

    /**
     * Picks the sentence/clause containing the strongest signal (a keyword or date match) as
     * a rough one-line summary, capped to a readable length. Falls back to sender + a
     * truncated excerpt when no clause with a clear signal is found.
     */
    private fun extractSummary(text: String, sender: String): String {
        val clauses = text.split(Regex("[.!?\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val candidate = clauses.firstOrNull { clause ->
            val lower = clause.lowercase()
            containsAny(lower, ACTION_PATTERNS) || containsAny(lower, WAITING_PATTERNS) ||
                containsAny(lower, DUE_PATTERNS) || DATE_PATTERN.containsMatchIn(clause)
        } ?: clauses.firstOrNull()

        val base = candidate ?: (if (sender.isNotBlank()) "$sender: $text" else text)
        val trimmed = base.trim()
        return if (trimmed.length > 80) trimmed.take(77).trimEnd() + "…" else trimmed
    }

    private val NOISE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.twitter.android",
        "com.facebook.katana", // Facebook feed app, distinct from com.facebook.orca (Messenger)
        "com.pinterest",
        "com.snapchat.android"
    )

    private val NOISE_PATTERNS = listOf(
        "liked your", "started following you", "new follower", "posted a new", "new video",
        "% off", "\\bsale\\b", "limited time", "flash sale", "trending now", "recommended for you",
        "new post from", "commented on your"
    ).map { Regex(it) }

    private val FYI_PATTERNS = listOf(
        "has been delivered", "out for delivery", "order shipped", "order confirmed",
        "payment received", "payment successful", "your receipt", "verification code",
        "\\botp\\b", "confirmation number", "booking confirmed", "successfully completed"
    ).map { Regex(it) }

    private val DUE_PATTERNS = listOf(
        "\\bdue\\b", "\\bby\\b", "expires", "expiring", "\\bdeadline\\b", "\\bbefore\\b"
    ).map { Regex(it) }

    private val ACTION_PATTERNS = listOf(
        "please send", "please share", "please confirm", "please review", "can you", "could you",
        "would you", "need you to", "\\bkindly\\b", "requesting", "requested that", "let me know if you can"
    ).map { Regex(it) }

    private val WAITING_PATTERNS = listOf(
        "i'll ", "i will ", "\\bon it\\b", "will do", "will send", "will get back", "will reply",
        "ill send", "ill get back"
    ).map { Regex(it) }

    private val DATE_PATTERN = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|tonight|" +
            "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}(?::\\d{2})?\\s?(?:am|pm))\\b",
        RegexOption.IGNORE_CASE
    )
}
