package com.futurepath.actionbox.classification

/**
 * Phase 5: the final classification decision, blending three signals into one score per
 * category before a winner is picked:
 *  - The rule engine ([NotificationClassifier]) — the PRIMARY signal.
 *  - Local user-correction history ([CorrectionLearning]) — folded into that same primary
 *    signal via [NotificationClassifier.scoreCategories]'s `learningBoosts` parameter, not
 *    weighted separately here. Rule engine and correction history are meant to dominate the
 *    decision TOGETHER, per the brief that drove this file's design, not as two independently
 *    tunable inputs.
 *  - The on-device ML model ([com.futurepath.actionbox.ml.TfliteNotificationClassifier]) — a
 *    MINOR supporting signal, added on top of the above.
 *
 * The ML model held out at 63.79% accuracy on entirely unseen phrasing vs. the rule engine's
 * 94.68% on the same-style benchmark (see tools/train_and_export_tflite_model.py's report) —
 * a >30 point reliability gap that [ML_WEIGHT] is deliberately sized around: distributed
 * across categories proportional to the model's own softmax probabilities (not just added to
 * its argmax), so:
 *  - An uncertain ML prediction (probabilities close to uniform across categories) barely
 *    moves anything, because no single category gets more than a sliver of [ML_WEIGHT].
 *  - A confident ML prediction that AGREES with the rule engine's pick reinforces it —
 *    widening its margin over the runner-up and modestly raising confidence.
 *  - A confident ML prediction that DISAGREES can only flip the outcome when the rule
 *    engine's own lead was already smaller than [ML_WEIGHT] — i.e. a genuine close tie, or a
 *    bare/near-zero rule-engine score (no real pattern evidence either way). It cannot
 *    overturn a rule-engine pick backed by real evidence (a score of [ML_WEIGHT] or more
 *    ahead of the runner-up), because the maximum the ML model can add to any one category is
 *    [ML_WEIGHT] itself.
 * This is a property of proportional blending, not a special-cased "if confidence is low,
 * consult the ML model" branch — there's no branch at all, just one weighted sum.
 */
object HybridClassifier {

    /**
     * Total points the ML model's prediction can add to a category's score, split across all
     * categories by the model's own probability for each. Sized like ONE weak rule-engine
     * pattern match (see [NotificationClassifier]'s DEFAULT_WEIGHT/ACTION_VERB_WEIGHT, both 2,
     * rounded up to give the ML signal slightly more say in genuine ties) — enough to swing a
     * close call or a near-zero rule-engine score, never enough on its own to overturn a
     * rule-engine pick with even a couple of real pattern matches behind it (score >= 4).
     */
    const val ML_WEIGHT = 3f

    /**
     * [ruleEngineScores]: from [NotificationClassifier.scoreCategories] — already includes
     * correction-learning boosts. [mlProbabilities]: the on-device model's softmax
     * distribution over categories (summing to ~1.0), or null/empty when the model is
     * unavailable — the result is then just [ruleEngineScores] promoted to Float, i.e. the
     * exact behavior of prior phases when no ML signal exists.
     */
    fun combineScores(
        ruleEngineScores: Map<ClassifiedState, Int>,
        mlProbabilities: Map<ClassifiedState, Float>?
    ): Map<ClassifiedState, Float> {
        val combined = ruleEngineScores.mapValues { it.value.toFloat() }
        if (mlProbabilities.isNullOrEmpty()) return combined
        return combined.mapValues { (state, score) -> score + ML_WEIGHT * (mlProbabilities[state] ?: 0f) }
    }

    /**
     * One-shot entry point: scores the rule engine (with correction-learning boosts folded
     * in), blends in the ML model's prediction, and returns the final decision —
     * [com.futurepath.actionbox.data.NotificationRepository.capture] calls this to produce
     * `classifiedState`/`confidenceScore`, replacing its prior direct call to
     * [NotificationClassifier.classify].
     */
    fun classify(
        sourceApp: String,
        sender: String,
        text: String,
        learningBoosts: Map<ClassifiedState, Int>,
        mlProbabilities: Map<ClassifiedState, Float>?
    ): ClassificationResult {
        val ruleEngineScores = NotificationClassifier.scoreCategories(sourceApp, sender, text, learningBoosts)
        val combined = combineScores(ruleEngineScores, mlProbabilities)
        return NotificationClassifier.resultFromScores(combined, text, sender)
    }
}
