package com.futurepath.actionbox.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the three qualitative properties Phase 5 explicitly asked for: the rule engine and
 * correction-learning history dominate the decision, and the ML model only meaningfully swings
 * the outcome in a close tie or against a low/no-confidence rule-engine result. These are
 * properties of [HybridClassifier.combineScores]'s proportional weighting, not of any special
 * casing — this test proves that arithmetic actually produces the intended behavior on
 * concrete score maps, independent of the real classifier/model producing those scores.
 */
class HybridClassifierTest {

    @Test
    fun `no ML signal falls back to the rule engine score alone, unchanged`() {
        val ruleScores = mapOf(
            ClassifiedState.ACTION to 6,
            ClassifiedState.REPLY to 2
        )
        val combined = HybridClassifier.combineScores(ruleScores, mlProbabilities = null)
        assertEquals(mapOf(ClassifiedState.ACTION to 6f, ClassifiedState.REPLY to 2f), combined)
    }

    @Test
    fun `a confident ML prediction cannot overturn a well-evidenced rule-engine pick`() {
        // Rule engine has real evidence for ACTION (a verb match plus a directed bonus and a
        // request-marker bonus is a typical strong case, comfortably ahead of the runner-up).
        val ruleScores = mapOf(
            ClassifiedState.ACTION to 8,
            ClassifiedState.REPLY to 2
        )
        // ML is fully confident in the WRONG category (REPLY) -- the worst case for it to
        // cause damage.
        val mlProbabilities = mapOf(
            ClassifiedState.ACTION to 0f,
            ClassifiedState.REPLY to 1f
        )
        val combined = HybridClassifier.combineScores(ruleScores, mlProbabilities)
        val winner = combined.maxByOrNull { it.value }!!.key
        assertEquals(ClassifiedState.ACTION, winner)
        // Confirms this isn't a coincidence of the specific numbers -- ACTION's lead over
        // REPLY (6 points) is larger than the entire pool of points ML can hand out
        // (ML_WEIGHT), so REPLY mathematically cannot catch up regardless of how ML splits it.
        assertTrue((ruleScores[ClassifiedState.ACTION]!! - ruleScores[ClassifiedState.REPLY]!!) > HybridClassifier.ML_WEIGHT)
    }

    @Test
    fun `a confident ML prediction CAN break a genuine close tie`() {
        val ruleScores = mapOf(
            ClassifiedState.WAITING to 2,
            ClassifiedState.ACTION to 2
        )
        val mlProbabilities = mapOf(
            ClassifiedState.WAITING to 0.9f,
            ClassifiedState.ACTION to 0.1f
        )
        val combined = HybridClassifier.combineScores(ruleScores, mlProbabilities)
        val winner = combined.maxByOrNull { it.value }!!.key
        assertEquals(ClassifiedState.WAITING, winner)
    }

    @Test
    fun `a confident ML prediction CAN override a bare, near-zero rule-engine score`() {
        val ruleScores = mapOf(
            ClassifiedState.FYI to 0,
            ClassifiedState.NOISE to 0,
            ClassifiedState.ACTION to 0,
            ClassifiedState.WAITING to 0,
            ClassifiedState.DEADLINE to 0,
            ClassifiedState.REPLY to 0
        )
        val mlProbabilities = mapOf(
            ClassifiedState.WAITING to 0.95f,
            ClassifiedState.ACTION to 0.05f
        )
        val combined = HybridClassifier.combineScores(ruleScores, mlProbabilities)
        val winner = combined.maxByOrNull { it.value }!!.key
        assertEquals(ClassifiedState.WAITING, winner)
    }

    @Test
    fun `an uncertain ML prediction barely moves an already-close rule-engine result`() {
        val ruleScores = mapOf(
            ClassifiedState.WAITING to 2,
            ClassifiedState.ACTION to 2
        )
        // Six-way near-uniform distribution -- maximally uncertain.
        val mlProbabilities = ClassifiedState.values().associateWith { 1f / ClassifiedState.values().size }
        val combined = HybridClassifier.combineScores(ruleScores, mlProbabilities)
        // Every category's ML contribution is the same tiny sliver, so the pre-existing rule
        // engine tie is preserved (both still equal) rather than being arbitrarily broken.
        assertEquals(combined[ClassifiedState.WAITING], combined[ClassifiedState.ACTION])
    }

    @Test
    fun `agreement between rule engine and ML raises confidence via a wider margin`() {
        val ruleScores = mapOf(ClassifiedState.DEADLINE to 5, ClassifiedState.FYI to 3)
        val agreeing = mapOf(ClassifiedState.DEADLINE to 1f, ClassifiedState.FYI to 0f)
        val disagreeing = mapOf(ClassifiedState.DEADLINE to 0f, ClassifiedState.FYI to 1f)

        val resultAgree = NotificationClassifier.resultFromScores(
            HybridClassifier.combineScores(ruleScores, agreeing), "text", "sender"
        )
        val resultDisagree = NotificationClassifier.resultFromScores(
            HybridClassifier.combineScores(ruleScores, disagreeing), "text", "sender"
        )
        val resultNoMl = NotificationClassifier.resultFromScores(
            HybridClassifier.combineScores(ruleScores, null), "text", "sender"
        )

        assertEquals(ClassifiedState.DEADLINE, resultAgree.state)
        assertEquals(ClassifiedState.DEADLINE, resultNoMl.state)
        assertTrue(resultAgree.confidence > resultNoMl.confidence)
        assertTrue(resultDisagree.confidence < resultNoMl.confidence)
    }
}
