package com.futurepath.actionbox.classification

/**
 * Pure decision logic for the local learning layer: given how many times a given
 * sender/app/phrase key has been corrected to each category, decides whether that history is
 * strong and consistent enough to trust, and how much to boost
 * [NotificationClassifier.classify]'s scoring for it. Kept separate from
 * [com.futurepath.actionbox.data.LearningPatternDao] (which does the actual SQL) so this can
 * be unit-tested on the JVM without Room or a device, exactly like [NotificationClassifier]
 * itself.
 */
object CorrectionLearning {

    /** Minimum consistent corrections before a pattern is trusted to bias classification. */
    const val STRONG_THRESHOLD = 3

    // Boost weights, sized against NotificationClassifier's own scoring scale (most pattern
    // matches score 2, a combo bonus scores 3, NOISE's app-package match scores 10). A
    // repeated exact phrase is the strongest signal (the same templated notification
    // recurring verbatim); sender is more reliable than app alone (an app carries many kinds
    // of message).
    const val SENDER_BOOST = 4
    const val APP_BOOST = 3
    const val PHRASE_BOOST = 5

    /**
     * [correctionCounts]: how many times this key has been corrected to each category.
     * Returns the dominant category if it has at least [STRONG_THRESHOLD] corrections and no
     * other category for the same key comes close — otherwise null. A key corrected to two
     * different categories about equally often isn't a reliable signal and shouldn't bias
     * anything.
     */
    fun strongCategory(correctionCounts: Map<ClassifiedState, Int>): ClassifiedState? {
        if (correctionCounts.isEmpty()) return null
        val (topState, topCount) = correctionCounts.maxByOrNull { it.value } ?: return null
        val runnerUp = correctionCounts.filterKeys { it != topState }.values.maxOrNull() ?: 0
        return if (topCount >= STRONG_THRESHOLD && topCount > runnerUp) topState else null
    }

    /**
     * Combines the dominant categories (if any) for a sender, app, and exact phrase into the
     * additive boost map [NotificationClassifier.classify]'s `learningBoosts` parameter
     * expects. All three sources stack additively — a sender AND a repeated phrase both
     * pointing to the same category reinforce each other rather than one overriding the other.
     */
    fun computeBoosts(
        senderCorrections: Map<ClassifiedState, Int>,
        appCorrections: Map<ClassifiedState, Int>,
        phraseCorrections: Map<ClassifiedState, Int>
    ): Map<ClassifiedState, Int> {
        val boosts = mutableMapOf<ClassifiedState, Int>()
        strongCategory(senderCorrections)?.let { boosts[it] = (boosts[it] ?: 0) + SENDER_BOOST }
        strongCategory(appCorrections)?.let { boosts[it] = (boosts[it] ?: 0) + APP_BOOST }
        strongCategory(phraseCorrections)?.let { boosts[it] = (boosts[it] ?: 0) + PHRASE_BOOST }
        return boosts
    }
}
