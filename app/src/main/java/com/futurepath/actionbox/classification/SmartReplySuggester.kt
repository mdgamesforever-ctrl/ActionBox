package com.futurepath.actionbox.classification

/**
 * Lightweight template/intent matching for quick-reply suggestions on REPLY-classified
 * notifications (Pro feature — see ui/components/NotificationCard.kt's smart-reply chips).
 * Deliberately simple pattern matching rather than the on-device LLM (Qwen) — this is the v1,
 * "start lightweight" version the feature's own spec calls for; a model-backed version can
 * replace or supplement this later without changing [suggest]'s contract (text in, suggestions
 * out).
 */
object SmartReplySuggester {

    // A time/date mention takes priority over a bare question mark when both are present (e.g.
    // "Are you free tomorrow?") since a confirm/reschedule reply is more actionable there than a
    // plain yes/no.
    private val TIME_DATE_PATTERN = Regex(
        "\\b(today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "\\d{1,2}(:\\d{2})?\\s?(am|pm)|\\d{1,2}/\\d{1,2})\\b",
        RegexOption.IGNORE_CASE
    )
    private val QUESTION_PATTERN = Regex("\\?\\s*$")

    private val TIME_DATE_SUGGESTIONS = listOf("Sounds good", "Can we reschedule?", "Confirmed")
    private val QUESTION_SUGGESTIONS = listOf("Yes", "No", "Let me get back to you")
    private val DEFAULT_SUGGESTIONS = listOf("👍", "On it", "Will reply soon")

    /** Up to 3 short quick-reply suggestions for [text]. Never empty for non-blank input —
     * [DEFAULT_SUGGESTIONS] covers anything that doesn't match a more specific pattern, since a
     * REPLY-classified notification always benefits from at least a generic acknowledgment
     * option. */
    fun suggest(text: String): List<String> {
        val trimmed = text.trim()
        return when {
            trimmed.isBlank() -> emptyList()
            TIME_DATE_PATTERN.containsMatchIn(trimmed) -> TIME_DATE_SUGGESTIONS
            QUESTION_PATTERN.containsMatchIn(trimmed) -> QUESTION_SUGGESTIONS
            else -> DEFAULT_SUGGESTIONS
        }
    }
}
