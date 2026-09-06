package com.futurepath.actionbox.classification

import org.junit.Test
import java.io.File

/**
 * Large-scale accuracy check for the rule-based [NotificationClassifier] against the shared
 * synthetic corpus in [SyntheticNotificationCorpus] (see that file for how it's built). Not a
 * correctness gate — it writes a full report (overall + per-category accuracy, every miss) to
 * build/classifier-corpus-report.txt since Gradle suppresses test stdout by default.
 */
class NotificationClassifierCorpusTest {

    @Test
    fun runCorpusAccuracyReport() {
        val cases = SyntheticNotificationCorpus.buildCorpus()
        val sb = StringBuilder()
        var correct = 0
        val misses = mutableListOf<String>()
        val byCategory = linkedMapOf<ClassifiedState, MutableList<Boolean>>()

        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            val result = NotificationClassifier.classify(case.sourceApp, case.sender, normalized)
            val isCorrect = result.state == case.expected
            if (isCorrect) correct++
            byCategory.getOrPut(case.expected) { mutableListOf() }.add(isCorrect)
            if (!isCorrect) {
                misses += "MISS [expected ${case.expected}, got ${result.state}] (confidence=${result.confidence}%) " +
                    "sourceApp=${case.sourceApp} sender=\"${case.sender}\" text=\"${case.text}\""
            }
        }

        sb.appendLine("=== ActionBox NotificationClassifier synthetic corpus accuracy report ===")
        sb.appendLine("Generated corpus size: ${cases.size} (from ${SyntheticNotificationCorpus.templates.size} templates)")
        sb.appendLine("Correct: $correct")
        sb.appendLine("Accuracy: ${"%.2f".format(100.0 * correct / cases.size)}%")
        sb.appendLine()
        sb.appendLine("-- By category --")
        for ((category, outcomes) in byCategory) {
            val catCorrect = outcomes.count { it }
            sb.appendLine("$category: $catCorrect/${outcomes.size} (${"%.1f".format(100.0 * catCorrect / outcomes.size)}%)")
        }
        sb.appendLine()
        sb.appendLine("-- Failures (${misses.size}) --")
        if (misses.isEmpty()) sb.appendLine("(none)") else misses.forEach { sb.appendLine(it) }

        val report = sb.toString()
        println(report)
        File("build/classifier-corpus-report.txt").apply {
            parentFile?.mkdirs()
            writeText(report)
        }
    }
}
