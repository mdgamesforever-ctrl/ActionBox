package com.futurepath.actionbox.classification

/**
 * Buckets a [ClassificationResult.confidence] score into an attention level for the feed UI.
 * As of Phase 5, that confidence comes from [HybridClassifier]'s blended rule-engine/
 * correction-learning/ML score rather than the rule engine alone — the 0-100 scale and these
 * thresholds are unchanged, since [HybridClassifier] normalizes to the same range regardless
 * of how many signals fed into it (see [NotificationClassifier.computeConfidence]'s doc for
 * how the ML model's contribution shows up here: agreement with the rule engine raises
 * confidence, disagreement lowers it).
 */
enum class ConfidenceTier {
    HIGH, // 90-100: auto-classify normally
    GOOD, // 75-89: auto-classify, show a small "tap to correct" affordance
    UNCERTAIN, // 55-74: classify but show a "not sure?" indicator
    LOW; // below 55: classifier already fell back to FYI and marked it for review

    companion object {
        fun fromScore(score: Int): ConfidenceTier = when {
            score >= 90 -> HIGH
            score >= 75 -> GOOD
            score >= 55 -> UNCERTAIN
            else -> LOW
        }
    }
}
