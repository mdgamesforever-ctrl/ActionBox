package com.futurepath.actionbox.classification

/**
 * Lightweight template/intent matching for quick-reply suggestions on REPLY-classified
 * notifications (Pro feature — see ui/components/NotificationCard.kt's smart-reply chips).
 * Deliberately simple pattern matching rather than the on-device LLM (Qwen) — this is the v1,
 * "start lightweight" version the feature's own spec calls for; a model-backed version can
 * replace or supplement this later without changing [suggest]'s contract (text in, suggestions
 * out).
 *
 * Both patterns below are intentionally broader than "ends with a literal '?'" or "contains an
 * exact day name" — real casual texting ("u free tmrw", "can you call") very often skips
 * question marks and formal date phrasing entirely. An earlier version of this object required a
 * trailing "?" and a narrow set of day/time tokens, which meant almost every real informally-
 * phrased message fell through to [DEFAULT_SUGGESTIONS] — technically "working" but
 * indistinguishable from always returning the same 3 suggestions. [suggest] is unit-tested
 * (SmartReplySuggesterTest) against exactly that kind of casual phrasing, not just clean,
 * fully-punctuated examples.
 */
object SmartReplySuggester {

    // A time/date mention takes priority over a question cue when both are present (e.g. "Are
    // you free tomorrow?") since a confirm/reschedule reply is more actionable there than a
    // plain yes/no.
    private val TIME_DATE_PATTERN = Regex(
        "\\b(today|tomorrow|tonight|tmrw|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "morning|afternoon|evening|noon|midnight|weekend|next week|" +
            "\\d{1,2}(:\\d{2})?\\s?(am|pm)|\\d{1,2}/\\d{1,2})\\b",
        RegexOption.IGNORE_CASE
    )

    // A literal "?" anywhere (not just a trailing one — "can you? no rush" still asks something)
    // OR a leading question-style cue phrase, which casual texting uses constantly without ever
    // typing a question mark at all ("can you call", "you around", "did you see this").
    private val QUESTION_PATTERN = Regex(
        "\\?|\\b(can|could|would|will)\\s+you\\b|\\b(are|is|do|does|did|have|has)\\s+(you|it|that|this|he|she|they)\\b|" +
            "\\byou\\s+(around|free|available|there|good)\\b",
        RegexOption.IGNORE_CASE
    )

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
