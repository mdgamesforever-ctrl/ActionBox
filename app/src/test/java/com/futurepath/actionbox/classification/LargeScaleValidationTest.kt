package com.futurepath.actionbox.classification

import org.junit.Test
import java.io.File

/**
 * The 10,000+ example final validation run: combines [SyntheticNotificationCorpus],
 * [DiverseNotificationCorpus], and [LargeScaleValidationCorpus] (which specifically exercises
 * the structural/metadata signals neither of the first two corpora meaningfully triggers) into
 * one large-scale accuracy report. Not a correctness gate — writes a full report (overall +
 * per-category accuracy + confidence stats + every miss) to
 * build/large-scale-validation-report.txt since Gradle suppresses test stdout by default.
 */
class LargeScaleValidationTest {

    @Test
    fun runLargeScaleValidation() {
        val cases = SyntheticNotificationCorpus.buildCorpus() +
            DiverseNotificationCorpus.buildCorpus() +
            LargeScaleValidationCorpus.buildCorpus()

        val sb = StringBuilder()
        var correct = 0
        val misses = mutableListOf<String>()
        val byCategory = linkedMapOf<ClassifiedState, MutableList<Boolean>>()
        val confidenceByCategory = linkedMapOf<ClassifiedState, MutableList<Int>>()

        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            val result = NotificationClassifier.classify(case.sourceApp, case.sender, normalized)
            val isCorrect = result.state == case.expected
            if (isCorrect) correct++
            byCategory.getOrPut(case.expected) { mutableListOf() }.add(isCorrect)
            if (isCorrect) confidenceByCategory.getOrPut(case.expected) { mutableListOf() }.add(result.confidence)
            if (!isCorrect) {
                misses += "MISS [expected ${case.expected}, got ${result.state}] (confidence=${result.confidence}%) " +
                    "sourceApp=${case.sourceApp} sender=\"${case.sender}\" text=\"${case.text}\""
            }
        }

        sb.appendLine("=== ActionBox NotificationClassifier LARGE-SCALE validation report ===")
        sb.appendLine("Corpus size: ${cases.size}")
        sb.appendLine("  SyntheticNotificationCorpus: ${SyntheticNotificationCorpus.buildCorpus().size}")
        sb.appendLine("  DiverseNotificationCorpus: ${DiverseNotificationCorpus.buildCorpus().size}")
        sb.appendLine("  LargeScaleValidationCorpus: ${LargeScaleValidationCorpus.buildCorpus().size}")
        sb.appendLine("Correct: $correct")
        sb.appendLine("Accuracy: ${"%.2f".format(100.0 * correct / cases.size)}%")
        sb.appendLine()
        sb.appendLine("-- By category --")
        for ((category, outcomes) in byCategory) {
            val catCorrect = outcomes.count { it }
            sb.appendLine("$category: $catCorrect/${outcomes.size} (${"%.1f".format(100.0 * catCorrect / outcomes.size)}%)")
        }
        sb.appendLine()
        sb.appendLine("-- Confidence stats (correctly-classified cases only) --")
        for ((category, confidences) in confidenceByCategory) {
            if (confidences.isEmpty()) continue
            val avg = confidences.average()
            val belowReview = confidences.count { it < 55 }
            sb.appendLine(
                "$category: avg=${"%.1f".format(avg)}% min=${confidences.min()}% max=${confidences.max()}% " +
                    "below-55%-threshold=$belowReview/${confidences.size}"
            )
        }
        sb.appendLine()
        sb.appendLine("-- Failures (${misses.size}) --")
        if (misses.isEmpty()) sb.appendLine("(none)") else misses.forEach { sb.appendLine(it) }

        val report = sb.toString()
        println(report)
        File("build/large-scale-validation-report.txt").apply {
            parentFile?.mkdirs()
            writeText(report)
        }
    }
}
