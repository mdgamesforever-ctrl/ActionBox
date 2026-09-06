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

    private fun export(cases: List<SyntheticNotificationCorpus.Case>, path: String) {
        val sb = StringBuilder()
        sb.appendLine("templateIndex\tlabel\tsourceApp\tsender\tnormalizedText")
        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            // Tabs/newlines can't appear in this corpus's generated text, so no escaping needed.
            sb.appendLine("${case.templateIndex}\t${case.expected}\t${case.sourceApp}\t${case.sender}\t$normalized")
        }
        File(path).apply {
            parentFile?.mkdirs()
            writeText(sb.toString())
        }
        println("Exported ${cases.size} examples to $path")
    }

    @Test
    fun exportNormalizedCorpus() {
        export(SyntheticNotificationCorpus.buildCorpus(), "build/ml-training-data.tsv")
    }

    /**
     * Exports exactly Phase 2's original 1128-example corpus (see
     * SyntheticNotificationCorpus.buildPhase2Corpus) so the on-device ML model can be evaluated
     * against the identical benchmark that produced the reported 94.68% rule-engine accuracy —
     * see tools/evaluate_ml_model_on_phase2_corpus.py.
     */
    @Test
    fun exportPhase2BenchmarkCorpus() {
        export(SyntheticNotificationCorpus.buildPhase2Corpus(), "build/phase2-benchmark-corpus.tsv")
    }
}
