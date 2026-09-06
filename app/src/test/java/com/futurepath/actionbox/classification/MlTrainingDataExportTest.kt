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
}
