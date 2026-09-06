package com.futurepath.actionbox.classification

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
