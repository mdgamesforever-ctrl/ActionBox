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

    @Test
    fun exportNormalizedCorpus() {
        val cases = SyntheticNotificationCorpus.buildCorpus()
        val sb = StringBuilder()
        sb.appendLine("templateIndex\tlabel\tsourceApp\tsender\tnormalizedText")
        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            // Tabs/newlines can't appear in this corpus's generated text, so no escaping needed.
            sb.appendLine("${case.templateIndex}\t${case.expected}\t${case.sourceApp}\t${case.sender}\t$normalized")
        }
        File("build/ml-training-data.tsv").apply {
            parentFile?.mkdirs()
            writeText(sb.toString())
        }
        println("Exported ${cases.size} examples to build/ml-training-data.tsv")
    }
}
