#!/usr/bin/env python3
"""
Evaluates the shipped on-device ML model (app/src/main/assets/models/notification_classifier
.tflite) ALONE -- no rule engine, no learning-layer boosts, nothing else in the pipeline --
against exactly Phase 2's original 1128-example corpus (94 templates x 12 variants), the same
benchmark that produced the reported 94.68% rule-engine-only accuracy. This is a fair
side-by-side: same input text (post-TextNormalizer.normalize()), same corpus, same category
set, only the classifier itself differs.

Runs the actual shipped .tflite file through a real TFLite interpreter (ai-edge-litert), not a
reimplementation of the model -- the only Python-side logic is feature extraction
(HashedTextVectorizer's port, verified bit-for-bit against real JVM String.hashCode() output
when it was first written for tools/train_and_export_tflite_model.py) and reading its softmax
output. Everything downstream of that vector is the real model computing the real graph.

Usage:
    python3 tools/evaluate_ml_model_on_phase2_corpus.py
Expects app/build/phase2-benchmark-corpus.tsv to already exist (run
`./gradlew testDebugUnitTest --tests "*MlTrainingDataExportTest*"` first).
"""
import csv
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter

from train_and_export_tflite_model import CATEGORIES, VECTOR_SIZE, vectorize

TSV_PATH = "app/build/phase2-benchmark-corpus.tsv"
MODEL_PATH = "app/src/main/assets/models/notification_classifier.tflite"
REPORT_PATH = "app/build/ml-model-phase2-benchmark-report.txt"
PHASE2_RULE_ENGINE_ACCURACY = 94.68  # reported in Phase 2, same 1128-example corpus


def load_dataset():
    labels, texts, source_apps, senders = [], [], [], []
    with open(TSV_PATH, newline="") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            labels.append(row["label"])
            texts.append(row["normalizedText"])
            source_apps.append(row["sourceApp"])
            senders.append(row["sender"])
    return labels, texts, source_apps, senders


def main():
    labels, texts, source_apps, senders = load_dataset()
    print(f"Loaded {len(labels)} examples from {TSV_PATH}")

    interpreter = Interpreter(model_path=MODEL_PATH)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    correct = 0
    per_category_total = {c: 0 for c in CATEGORIES}
    per_category_correct = {c: 0 for c in CATEGORIES}
    misses = []

    for label, text, source_app, sender in zip(labels, texts, source_apps, senders):
        vec = vectorize(text).reshape(1, VECTOR_SIZE).astype(np.float32)
        interpreter.set_tensor(input_details[0]["index"], vec)
        interpreter.invoke()
        probs = interpreter.get_tensor(output_details[0]["index"])[0]
        pred_idx = int(np.argmax(probs))
        pred_label = CATEGORIES[pred_idx]
        confidence = float(probs[pred_idx]) * 100

        per_category_total[label] += 1
        is_correct = pred_label == label
        if is_correct:
            correct += 1
            per_category_correct[label] += 1
        else:
            misses.append(
                f"MISS [expected {label}, got {pred_label}] (confidence={confidence:.1f}%) "
                f'sourceApp={source_app} sender="{sender}" text="{text}"'
            )

    accuracy = 100.0 * correct / len(labels)

    lines = []
    lines.append("=== ActionBox on-device ML model (alone, no rule engine): Phase 2 benchmark report ===")
    lines.append(f"Corpus: {len(labels)} examples (Phase 2's original 94 templates x 12 variants, "
                 f"identical to the corpus behind the reported 94.68% rule-engine accuracy)")
    lines.append(f"Classifier under test: notification_classifier.tflite ONLY -- no "
                 f"NotificationClassifier rule engine, no CorrectionLearning boosts")
    lines.append("")
    lines.append(f"Correct: {correct}/{len(labels)}")
    lines.append(f"Accuracy: {accuracy:.2f}%")
    lines.append("")
    lines.append(f"-- Comparison to Phase 2 rule-engine-only accuracy on the same corpus --")
    lines.append(f"Rule engine (Phase 2):    {PHASE2_RULE_ENGINE_ACCURACY:.2f}%")
    lines.append(f"ML model alone (Phase 4): {accuracy:.2f}%")
    diff = accuracy - PHASE2_RULE_ENGINE_ACCURACY
    lines.append(f"Difference: {diff:+.2f} percentage points ({'ML model ahead' if diff > 0 else 'rule engine ahead'})")
    lines.append("")
    lines.append("CAVEAT -- this is not a clean apples-to-apples generalization comparison: these")
    lines.append("exact 1128 rows are a SUBSET of the training set the shipped model's parameters")
    lines.append("were directly fit to (see tools/train_and_export_tflite_model.py's final refit on")
    lines.append("all data). The rule engine's regexes, by contrast, were hand-tuned by inspection,")
    lines.append("not statistically optimized against this corpus's rows. So this number mixes in a")
    lines.append("large dose of the ML model simply having memorized phrasing it was trained on.")
    lines.append("For the model's accuracy on truly UNSEEN phrasing -- the number that actually")
    lines.append("matters for real-world expectations -- see the HEADLINE FINDING at the top of")
    lines.append("app/build/ml-model-accuracy-report.txt, not this number or the row-level split")
    lines.append("also printed there.")
    lines.append("")
    lines.append("-- By category --")
    for category in CATEGORIES:
        total = per_category_total[category]
        cat_correct = per_category_correct[category]
        pct = 100.0 * cat_correct / total if total else 0.0
        lines.append(f"{category}: {cat_correct}/{total} ({pct:.1f}%)")
    lines.append("")
    lines.append(f"-- Failures ({len(misses)}) --")
    if misses:
        lines.extend(misses)
    else:
        lines.append("(none)")

    report = "\n".join(lines)
    print()
    print(report)
    with open(REPORT_PATH, "w") as f:
        f.write(report + "\n")
    print(f"\nWrote {REPORT_PATH}")


if __name__ == "__main__":
    sys.exit(main())
