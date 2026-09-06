#!/usr/bin/env python3
"""
Final Phase 5 checkpoint: runs the COMPLETE hybrid system (rule engine + on-device ML model +
correction-learning) against the same 1128-example benchmark corpus (Phase 2's original 94
templates x 12 variants) every prior phase's headline number was measured against, for a fully
apples-to-apples cross-phase comparison.

This uses the real shipped .tflite model for ML inference (via a real TFLite interpreter,
ai-edge-litert -- see tools/evaluate_ml_model_on_phase2_corpus.py's doc for why that's a
faithful stand-in for the Android runtime) and the real rule-engine scores (exported by
MlTrainingDataExportTest.exportRuleEngineScoresForHybridBenchmark via
NotificationClassifier.scoreCategories -- the exact function HybridClassifier.classify calls),
combined with HybridClassifier's exact arithmetic ported here: combined[state] = ruleScore[state]
+ ML_WEIGHT * mlProbability[state], winner picked via the same TIE_BREAK_ORDER and topScore<=0
-> FYI fallback as NotificationClassifier.resultFromScores. Not a reimplementation of the rule
engine itself (that stays in Kotlin, read from the export) -- only the small blend-and-decide
step is ported, to avoid a second copy of ~200 lines of regex scoring logic to keep in sync.

CORRECTION-LEARNING CAVEAT: learningBoosts is empty for every row in the export, because this
is a fresh benchmark corpus with no accumulated correction history -- CorrectionLearning needs
3+ consistent PRIOR REAL corrections per sender/app/phrase before it contributes anything (see
CorrectionLearningDao.STRONG_THRESHOLD). A single static benchmark pass cannot exercise "the
local, on-device learning layer that biases classification toward a category this
sender/app/phrase has been consistently corrected to before" in any other way, since there is
nothing to simulate corrections against without changing what the corpus IS. This is stated
here plainly rather than glossed over: this run measures rule engine + ML, not rule engine + ML
+ correction learning, because the third component's precondition (accumulated real usage) does
not and cannot exist in this sandbox.

Usage:
    python3 tools/evaluate_hybrid_pipeline.py
Expects app/build/hybrid-benchmark-rule-scores.tsv to already exist (run
`./gradlew testDebugUnitTest --tests "*MlTrainingDataExportTest*"` first).
"""
import csv
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter

from train_and_export_tflite_model import CATEGORIES, VECTOR_SIZE, vectorize

TSV_PATH = "app/build/hybrid-benchmark-rule-scores.tsv"
MODEL_PATH = "app/src/main/assets/models/notification_classifier.tflite"
REPORT_PATH = "app/build/hybrid-pipeline-benchmark-report.txt"

# Must match HybridClassifier.ML_WEIGHT exactly.
ML_WEIGHT = 3.0

# Must match NotificationClassifier.TIE_BREAK_ORDER exactly.
TIE_BREAK_ORDER = ["NOISE", "DEADLINE", "ACTION", "WAITING", "FYI", "REPLY"]

# Cross-phase checkpoints, all measured on this identical 1128-example corpus except where
# noted. Recorded here (not computed) since they're each a fact about a specific prior
# artifact/model, not something this script re-derives.
CHECKPOINTS = [
    ("Phase 1 (capture only)", "N/A -- no classifier existed yet"),
    ("Phase 2 (rule engine, closed)", "94.68%"),
    ("Phase 3 (rule engine + untrained ML stub)", "N/A -- placeholder model, not a meaningful accuracy number by design"),
    ("Phase 4 (ML model alone, current shipped model)", "93.79% -- INFLATED: this corpus is a subset of the model's own training data (see tools/evaluate_ml_model_on_phase2_corpus.py); 63.79% on genuinely unseen phrasing is the honest number for this model"),
]


def load_dataset():
    labels, texts, source_apps, senders, rule_scores = [], [], [], [], []
    with open(TSV_PATH, newline="") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            labels.append(row["label"])
            texts.append(row["normalizedText"])
            source_apps.append(row["sourceApp"])
            senders.append(row["sender"])
            rule_scores.append({cat: int(row[f"score_{cat}"]) for cat in CATEGORIES})
    return labels, texts, source_apps, senders, rule_scores


def combine_and_decide(rule_scores: dict, ml_probabilities: dict) -> str:
    """Ports HybridClassifier.combineScores + NotificationClassifier.resultFromScores's
    winner-selection exactly (confidence is not needed for an accuracy-only benchmark, so it's
    not ported)."""
    combined = {
        cat: rule_scores[cat] + ML_WEIGHT * ml_probabilities[i]
        for i, cat in enumerate(CATEGORIES)
    }
    top_score = max(combined.values())
    winner = next(cat for cat in TIE_BREAK_ORDER if combined[cat] == top_score)
    return "FYI" if top_score <= 0 else winner


def per_category_report(y_true, y_pred):
    lines = []
    for category in CATEGORIES:
        total = sum(1 for t in y_true if t == category)
        correct = sum(1 for t, p in zip(y_true, y_pred) if t == category and p == category)
        pct = 100.0 * correct / total if total else 0.0
        lines.append(f"  {category}: {correct}/{total} ({pct:.1f}%)")
    return "\n".join(lines)


def main():
    labels, texts, source_apps, senders, rule_scores = load_dataset()
    print(f"Loaded {len(labels)} examples from {TSV_PATH}")

    interpreter = Interpreter(model_path=MODEL_PATH)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    predictions = []
    misses = []
    for label, text, source_app, sender, scores in zip(labels, texts, source_apps, senders, rule_scores):
        vec = vectorize(text).reshape(1, VECTOR_SIZE).astype(np.float32)
        interpreter.set_tensor(input_details[0]["index"], vec)
        interpreter.invoke()
        ml_probabilities = interpreter.get_tensor(output_details[0]["index"])[0]

        prediction = combine_and_decide(scores, ml_probabilities)
        predictions.append(prediction)
        if prediction != label:
            misses.append(f'MISS [expected {label}, got {prediction}] sourceApp={source_app} '
                           f'sender="{sender}" text="{text}" rule_scores={scores} '
                           f'ml_probs={dict(zip(CATEGORIES, [round(float(p), 3) for p in ml_probabilities]))}')

    correct = sum(1 for t, p in zip(labels, predictions) if t == p)
    accuracy = 100.0 * correct / len(labels)

    lines = []
    lines.append("=== ActionBox FULL HYBRID SYSTEM: final Phase 5 benchmark ===")
    lines.append(f"Corpus: {len(labels)} examples (Phase 2's original 94 templates x 12 "
                 f"variants -- identical corpus used for every phase's headline number below)")
    lines.append("Pipeline under test: HybridClassifier (rule engine + on-device ML model), "
                  "combined via the exact production formula")
    lines.append("")
    lines.append("CORRECTION-LEARNING CAVEAT: contributes nothing in this run. It requires 3+")
    lines.append("consistent PRIOR REAL corrections per sender/app/phrase before it produces any")
    lines.append("boost, and this is a fresh benchmark pass with no accumulated correction")
    lines.append("history -- there is no real usage to have generated one. This measures rule")
    lines.append("engine + ML only; the correction-learning component's precondition cannot be")
    lines.append("met by a static corpus run, in this sandbox or otherwise.")
    lines.append("")
    lines.append(f"Correct: {correct}/{len(labels)}")
    lines.append(f"Accuracy: {accuracy:.2f}%")
    lines.append("")
    lines.append("-- By category --")
    lines.append(per_category_report(labels, predictions))
    lines.append("")
    lines.append("=== Cross-phase progression (same 1128-example corpus throughout) ===")
    for phase, result in CHECKPOINTS:
        lines.append(f"{phase}: {result}")
    lines.append(f"Phase 5 (full hybrid system, this run): {accuracy:.2f}%")
    lines.append(per_category_report(labels, predictions))
    lines.append("")
    diff_vs_rule_engine = accuracy - 94.68
    lines.append(f"Hybrid vs. rule-engine-alone (Phase 2, 94.68%): {diff_vs_rule_engine:+.2f} "
                 f"percentage points")
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
