package com.futurepath.actionbox.classification

import org.junit.Test
import java.io.File

/**
 * Not a test of production code — a one-shot data export step for training the on-device ML
 * model (see tools/train_and_export_tflite_model.py). Runs the shared synthetic corpus through
 * the real [TextNormalizer.normalize], the exact same call [com.futurepath.actionbox.data
 * .NotificationRepository.capture] makes before either classifier ever sees the text, so the
 * training data is normalized identically to what the on-device model will see at inference
 * time. Writes build/ml-training-data.tsv (templateIndex, label, sourceApp, sender,
 * normalizedText) for the Python training script to read; running this via Gradle rather than
 * reimplementing TextNormalizer in Python is what guarantees that parity.
 */
class MlTrainingDataExportTest {

    private fun export(cases: List<SyntheticNotificationCorpus.Case>, path: String, source: String) {
        val sb = StringBuilder()
        sb.appendLine("templateIndex\tlabel\tsourceApp\tsender\tsource\tnormalizedText")
        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            // Tabs/newlines can't appear in this corpus's generated text, so no escaping needed.
            sb.appendLine("${case.templateIndex}\t${case.expected}\t${case.sourceApp}\t${case.sender}\t$source\t$normalized")
        }
        File(path).apply {
            parentFile?.mkdirs()
            writeText(sb.toString())
        }
        println("Exported ${cases.size} ($source) examples to $path")
    }

    /**
     * Combines both corpora for training: [SyntheticNotificationCorpus] (template+substitution
     * style, 122 templates) and [DiverseNotificationCorpus] (246 independently-written
     * sentences — see that file's doc for why it exists). Diverse's templateIndex values are
     * offset past Synthetic's so the two corpora's template groups never collide when the
     * Python training script groups rows by template for held-out evaluation. The `source`
     * column lets that script balance each source's total training weight (see its doc) —
     * without it, the templated corpus's much higher row count per structure would still
     * dominate the loss even with 246 diverse structures present, undermining the point of
     * adding them.
     */
    @Test
    fun exportNormalizedCorpus() {
        val templateIndexOffset = SyntheticNotificationCorpus.templates.size
        val synthetic = SyntheticNotificationCorpus.buildCorpus()
        val diverse = DiverseNotificationCorpus.buildCorpus()
            .map { it.copy(templateIndex = it.templateIndex + templateIndexOffset) }

        val sb = StringBuilder()
        sb.appendLine("templateIndex\tlabel\tsourceApp\tsender\tsource\tnormalizedText")
        for ((cases, source) in listOf(synthetic to "synthetic", diverse to "diverse")) {
            for (case in cases) {
                val normalized = TextNormalizer.normalize(case.text)
                sb.appendLine("${case.templateIndex}\t${case.expected}\t${case.sourceApp}\t${case.sender}\t$source\t$normalized")
            }
        }
        File("build/ml-training-data.tsv").apply {
            parentFile?.mkdirs()
            writeText(sb.toString())
        }
        println("Exported ${synthetic.size} synthetic + ${diverse.size} diverse = " +
            "${synthetic.size + diverse.size} examples to build/ml-training-data.tsv")
    }

    /**
     * Exports exactly Phase 2's original 1128-example corpus (see
     * SyntheticNotificationCorpus.buildPhase2Corpus) so the on-device ML model can be evaluated
     * against the identical benchmark that produced the reported 94.68% rule-engine accuracy —
     * see tools/evaluate_ml_model_on_phase2_corpus.py.
     */
    @Test
    fun exportPhase2BenchmarkCorpus() {
        export(SyntheticNotificationCorpus.buildPhase2Corpus(), "build/phase2-benchmark-corpus.tsv", source = "synthetic")
    }

    /**
     * Exports the rule engine's raw per-category scores (via
     * [NotificationClassifier.scoreCategories], the exact same function [HybridClassifier]
     * calls) for the same Phase 2 benchmark corpus, so tools/evaluate_hybrid_pipeline.py can
     * blend in real ML inference using HybridClassifier's exact arithmetic without needing to
     * reimplement the rule engine's regex scoring in Python — that would be a second copy of
     * ~200 lines of pattern logic to keep in sync, versus reading six numbers per row here.
     *
     * learningBoosts is empty for every row: this is a fresh benchmark corpus with no
     * accumulated correction history (CorrectionLearning needs 3+ consistent prior real
     * corrections per sender/app/phrase before it contributes anything), so the
     * correction-learning layer genuinely contributes nothing to this particular run — see
     * that script's report for why this is stated explicitly rather than silently glossed
     * over.
     */
    @Test
    fun exportRuleEngineScoresForHybridBenchmark() {
        val cases = SyntheticNotificationCorpus.buildPhase2Corpus()
        val sb = StringBuilder()
        val categories = ClassifiedState.values()
        sb.appendLine("label\tsourceApp\tsender\tnormalizedText\t" + categories.joinToString("\t") { "score_$it" })
        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            val scores = NotificationClassifier.scoreCategories(case.sourceApp, case.sender, normalized, emptyMap())
            val scoreColumns = categories.joinToString("\t") { (scores[it] ?: 0).toString() }
            sb.appendLine("${case.expected}\t${case.sourceApp}\t${case.sender}\t$normalized\t$scoreColumns")
        }
        File("build/hybrid-benchmark-rule-scores.tsv").apply {
            parentFile?.mkdirs()
            writeText(sb.toString())
        }
        println("Exported ${cases.size} rule-engine-scored examples to build/hybrid-benchmark-rule-scores.tsv")
    }
}
