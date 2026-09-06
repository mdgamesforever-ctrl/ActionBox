#!/usr/bin/env python3
"""
Trains ActionBox's on-device notification classifier and exports it as a .tflite model,
replacing the untrained placeholder from build_stub_tflite_model.py (Phase 3).

Model: multinomial logistic regression (softmax regression) over a feature-hashed bag-of-words
vector -- i.e. exactly the FULLY_CONNECTED(VECTOR_SIZE -> 6) + SOFTMAX graph the placeholder
already established, just with real learned weights instead of untrained noise. Chosen because:
  - It's compact: ~130KB of float32 weights (128*6 + 6 params), no separate vocab/embedding
    table needed at all thanks to feature hashing (see below) -- versus a learned-embedding or
    transformer approach, which would need a vocab file shipped alongside the model and is
    overkill for 6-way short-text classification.
  - It's exactly representable by the two TFLite ops already wired up on the Android side
    (ml/TfliteNotificationClassifier.kt) and by the flatbuffer-building code below, adapted
    from build_stub_tflite_model.py -- no new ops, no new Android-side code needed.
  - Logistic regression's decision function is EXACTLY softmax(x . W^T + b) -- there is no
    approximation gap between what sklearn learns and what the exported graph computes, so a
    held-out accuracy number computed in sklearn transfers directly to the shipped model.

Feature extraction (HashedTextVectorizer equivalent): tokenizes on whitespace, hashes each
token via Java's String.hashCode() (Kotlin's String.hashCode() is the same algorithm), buckets
into VECTOR_SIZE slots via (hash & 0x7fffffff) % VECTOR_SIZE, counts occurrences, then
L1-normalizes. This MUST match classification/HashedTextVectorizer.kt exactly, or the shipped
model will have been trained on a different feature space than what it sees at inference time.
`java_string_hashcode`/`vectorize` below are that Python-side port, verified bit-for-bit
against real JVM-computed String.hashCode() output for ~20 representative tokens (including
punctuation-bearing ones like "you're" and slang like "2nite"/"b4") before this script was
trusted to train anything.

Training data: the Phase 2 synthetic corpus (SyntheticNotificationCorpus.kt, expanded from
1128 to 2256 examples for this phase), exported by MlTrainingDataExportTest through the real
TextNormalizer.normalize() so training text is normalized identically to inference-time text --
see that test's doc for why this runs through the JVM rather than reimplementing TextNormalizer
in Python. No real corrected examples were available to include: this is a fresh sandbox with
no connected device or user data, so NotificationEntity.correctedState has never been
populated anywhere accessible to this training run.

Usage:
    python3 tools/train_and_export_tflite_model.py
Expects app/build/ml-training-data.tsv to already exist (run
`./gradlew testDebugUnitTest --tests "*MlTrainingDataExportTest*"` first).
"""
import csv
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedGroupKFold, train_test_split

import flatbuffers
from tflite.Model import (
    ModelStart, ModelAddVersion, ModelAddOperatorCodes, ModelAddSubgraphs,
    ModelAddDescription, ModelAddBuffers, ModelEnd,
    ModelStartBuffersVector, ModelStartSubgraphsVector, ModelStartOperatorCodesVector,
)
from tflite.SubGraph import (
    SubGraphStart, SubGraphAddTensors, SubGraphAddInputs, SubGraphAddOutputs,
    SubGraphAddOperators, SubGraphAddName, SubGraphEnd,
    SubGraphStartTensorsVector, SubGraphStartInputsVector, SubGraphStartOutputsVector,
    SubGraphStartOperatorsVector,
)
from tflite.Tensor import (
    TensorStart, TensorAddShape, TensorAddType, TensorAddBuffer, TensorAddName, TensorEnd,
    TensorStartShapeVector,
)
from tflite.Buffer import BufferStart, BufferAddData, BufferEnd
from tflite.Operator import (
    OperatorStart, OperatorAddOpcodeIndex, OperatorAddInputs, OperatorAddOutputs,
    OperatorAddBuiltinOptionsType, OperatorAddBuiltinOptions, OperatorEnd,
    OperatorStartInputsVector, OperatorStartOutputsVector,
)
from tflite.OperatorCode import (
    OperatorCodeStart, OperatorCodeAddDeprecatedBuiltinCode, OperatorCodeAddBuiltinCode,
    OperatorCodeAddVersion, OperatorCodeEnd,
)
from tflite.FullyConnectedOptions import (
    FullyConnectedOptionsStart, FullyConnectedOptionsAddFusedActivationFunction,
    FullyConnectedOptionsEnd,
)
from tflite.SoftmaxOptions import SoftmaxOptionsStart, SoftmaxOptionsAddBeta, SoftmaxOptionsEnd
from tflite.BuiltinOperator import BuiltinOperator
from tflite.BuiltinOptions import BuiltinOptions
from tflite.TensorType import TensorType
from tflite.ActivationFunctionType import ActivationFunctionType

VECTOR_SIZE = 128  # must match HashedTextVectorizer.VECTOR_SIZE
# ClassifiedState.values() declaration order -- the model's 6 output units mean this order.
CATEGORIES = ["ACTION", "REPLY", "WAITING", "DEADLINE", "FYI", "NOISE"]
TSV_PATH = "app/build/ml-training-data.tsv"
OUTPUT_PATH = "app/src/main/assets/models/notification_classifier.tflite"
REPORT_PATH = "app/build/ml-model-accuracy-report.txt"
RANDOM_STATE = 42


def java_string_hashcode(s: str) -> int:
    """Port of Java/Kotlin's String.hashCode(): s[0]*31^(n-1) + ... + s[n-1], mod 2**32."""
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h


def vectorize(normalized_text: str) -> np.ndarray:
    """Port of HashedTextVectorizer.vectorize() -- must stay in lockstep with the Kotlin."""
    vector = np.zeros(VECTOR_SIZE, dtype=np.float32)
    tokens = [t for t in normalized_text.lower().split() if t]
    if not tokens:
        return vector
    for token in tokens:
        bucket = (java_string_hashcode(token) & 0x7FFFFFFF) % VECTOR_SIZE
        vector[bucket] += 1.0
    vector /= len(tokens)
    return vector


def load_dataset():
    template_ids, labels, texts, sources = [], [], [], []
    with open(TSV_PATH, newline="") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            template_ids.append(int(row["templateIndex"]))
            labels.append(row["label"])
            texts.append(row["normalizedText"])
            # Older exports (before the diversified corpus) have no "source" column; treat
            # everything as "synthetic" so this script still runs against them.
            sources.append(row.get("source", "synthetic"))
    X = np.stack([vectorize(t) for t in texts])
    y = np.array([CATEGORIES.index(label) for label in labels])
    return X, y, np.array(template_ids), texts, np.array(sources)


def source_balanced_weights(sources: np.ndarray) -> np.ndarray:
    """
    Without this, the templated corpus's much higher row count per sentence structure (each
    of 122 templates repeated up to 12x with substituted names/days/amounts) would still
    dominate the training loss even with 246 diverse, independently-written structures also
    present -- 1464 templated rows vs. ~270 diverse ones is a >5:1 imbalance by row count,
    though not by structure count. Scaling each row's weight so every distinct `source` value
    contributes the SAME TOTAL weight overall forces the model to actually fit the diverse
    examples' patterns rather than being swamped by repeated structure from the templated
    ones.
    """
    weights = np.ones(len(sources), dtype=np.float64)
    unique_sources, counts = np.unique(sources, return_counts=True)
    target_weight_per_source = len(sources) / len(unique_sources)
    for source, count in zip(unique_sources, counts):
        weights[sources == source] = target_weight_per_source / count
    return weights


def per_category_report(y_true, y_pred):
    lines = []
    for idx, category in enumerate(CATEGORIES):
        mask = y_true == idx
        total = int(mask.sum())
        correct = int((y_pred[mask] == idx).sum())
        pct = 100.0 * correct / total if total else 0.0
        lines.append(f"  {category}: {correct}/{total} ({pct:.1f}%)")
    return "\n".join(lines)


def template_level_split(template_ids, y, test_fraction=0.2, seed=RANDOM_STATE):
    """
    Holds out entire templates (not just rows) per category, so the held-out set contains
    phrasing patterns the model never saw ANY variant of during training -- a stricter,
    more honest generalization estimate than a row-level split, since a row-level split still
    lets sibling variants of the same sentence template (same wording, different name/day/
    amount) leak into training.
    """
    rng = np.random.default_rng(seed)
    template_to_category = {}
    for tid, label in zip(template_ids, y):
        template_to_category[tid] = label

    test_templates = set()
    by_category = {}
    for tid, label in template_to_category.items():
        by_category.setdefault(label, []).append(tid)
    for label, tids in by_category.items():
        tids = sorted(tids)
        rng.shuffle(tids)
        n_test = max(1, round(len(tids) * test_fraction))
        test_templates.update(tids[:n_test])

    is_test = np.array([tid in test_templates for tid in template_ids])
    return ~is_test, is_test


def train_logreg(X, y, C, class_weight, sample_weight=None):
    model = LogisticRegression(
        solver="lbfgs", C=C, max_iter=2000, class_weight=class_weight, random_state=RANDOM_STATE
    )
    model.fit(X, y, sample_weight=sample_weight)
    return model


def grouped_cv_accuracy(X, y, groups, C, class_weight, sample_weight):
    """
    Hand-rolled grouped 5-fold CV (rather than cross_val_score) so sample_weight can be passed
    to .fit() without sklearn's metadata-routing config, which is global, version-sensitive,
    and adds failure modes unrelated to this script's actual job.
    """
    cv = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
    accuracies = []
    for train_idx, val_idx in cv.split(X, y, groups=groups):
        model = LogisticRegression(solver="lbfgs", C=C, max_iter=2000, class_weight=class_weight)
        model.fit(X[train_idx], y[train_idx], sample_weight=sample_weight[train_idx])
        accuracies.append(model.score(X[val_idx], y[val_idx]))
    return float(np.mean(accuracies))


def pick_best_hyperparams(X, y, template_ids, sample_weight):
    """
    Picks (C, class_weight) via GROUPED cross-validation (grouped by template, i.e. by
    sentence pattern) -- plain row-level StratifiedKFold would let sibling variants of the
    same template (same fixed wording, different substituted name/day/amount) split across
    train and validation within a fold, which rewards memorizing the template's fixed
    phrasing rather than generalizing. That failure mode is exactly what the first version of
    this function did: it picked the least-regularized candidate (C=30) because it
    "generalized perfectly" to more rows of the same templates, then that model did far worse
    on genuinely unseen phrasing. Grouping by template so no template's rows appear on both
    sides of a fold is what makes this selection honest.

    class_weight="balanced" is in the search too: after expanding WAITING/FYI/NOISE with more
    templates, DEADLINE became the smallest category by row count, and an unweighted model's
    template-holdout accuracy on DEADLINE specifically dropped hard (92% -> ~35-53% across C)
    even though DEADLINE's own templates didn't change -- balancing consistently recovered
    most of that with only a small cost to the now-larger categories.

    sample_weight (source-balancing, see source_balanced_weights) is applied during fit in
    every fold too, for the same reason it's applied to the final model: without it, the
    templated corpus's row-count advantage would otherwise steer hyperparameter selection
    itself, not just the final fit.
    """
    C_candidates = [0.1, 0.3, 1.0, 3.0, 10.0, 30.0]
    class_weight_candidates = [None, "balanced"]
    best_params, best_score = None, -1.0
    for class_weight in class_weight_candidates:
        for C in C_candidates:
            mean_score = grouped_cv_accuracy(X, y, template_ids, C, class_weight, sample_weight)
            print(f"  C={C}, class_weight={class_weight}: "
                  f"5-fold grouped-by-template CV accuracy = {mean_score * 100:.2f}%")
            if mean_score > best_score:
                best_params, best_score = (C, class_weight), mean_score
    return best_params, best_score


# ---- FlatBuffer construction (adapted from build_stub_tflite_model.py) --------------------

def int_vector(builder, start_fn, values):
    start_fn(builder, len(values))
    for v in reversed(values):
        builder.PrependInt32(v)
    return builder.EndVector()


def offset_vector(builder, start_fn, offsets):
    start_fn(builder, len(offsets))
    for o in reversed(offsets):
        builder.PrependUOffsetTRelative(o)
    return builder.EndVector()


def make_buffer(builder, data_bytes):
    data_off = builder.CreateByteVector(data_bytes) if data_bytes is not None else None
    BufferStart(builder)
    if data_off is not None:
        BufferAddData(builder, data_off)
    return BufferEnd(builder)


def make_tensor(builder, shape, tensor_type, buffer_index, name):
    name_off = builder.CreateString(name)
    shape_off = int_vector(builder, TensorStartShapeVector, shape)
    TensorStart(builder)
    TensorAddShape(builder, shape_off)
    TensorAddType(builder, tensor_type)
    TensorAddBuffer(builder, buffer_index)
    TensorAddName(builder, name_off)
    return TensorEnd(builder)


def build_tflite_model(weights: np.ndarray, bias: np.ndarray, description: str) -> bytes:
    assert weights.shape == (len(CATEGORIES), VECTOR_SIZE)
    assert bias.shape == (len(CATEGORIES),)
    weights = weights.astype("<f4")
    bias = bias.astype("<f4")

    builder = flatbuffers.Builder(4096)

    buf_empty = make_buffer(builder, None)
    buf_weights = make_buffer(builder, weights.tobytes())
    buf_bias = make_buffer(builder, bias.tobytes())
    buf_fc_out = make_buffer(builder, None)
    buf_softmax_out = make_buffer(builder, None)
    buffers_vec = offset_vector(
        builder, ModelStartBuffersVector,
        [buf_empty, buf_weights, buf_bias, buf_fc_out, buf_softmax_out]
    )

    t_input = make_tensor(builder, [1, VECTOR_SIZE], TensorType.FLOAT32, 0, "input_vector")
    t_weights = make_tensor(builder, [len(CATEGORIES), VECTOR_SIZE], TensorType.FLOAT32, 1, "fc_weights")
    t_bias = make_tensor(builder, [len(CATEGORIES)], TensorType.FLOAT32, 2, "fc_bias")
    t_fc_out = make_tensor(builder, [1, len(CATEGORIES)], TensorType.FLOAT32, 3, "fc_output")
    t_output = make_tensor(builder, [1, len(CATEGORIES)], TensorType.FLOAT32, 4, "category_probabilities")
    tensors_vec = offset_vector(
        builder, SubGraphStartTensorsVector,
        [t_input, t_weights, t_bias, t_fc_out, t_output]
    )

    FullyConnectedOptionsStart(builder)
    FullyConnectedOptionsAddFusedActivationFunction(builder, ActivationFunctionType.NONE)
    fc_options = FullyConnectedOptionsEnd(builder)

    fc_inputs = int_vector(builder, OperatorStartInputsVector, [0, 1, 2])
    fc_outputs = int_vector(builder, OperatorStartOutputsVector, [3])
    OperatorStart(builder)
    OperatorAddOpcodeIndex(builder, 0)
    OperatorAddInputs(builder, fc_inputs)
    OperatorAddOutputs(builder, fc_outputs)
    OperatorAddBuiltinOptionsType(builder, BuiltinOptions.FullyConnectedOptions)
    OperatorAddBuiltinOptions(builder, fc_options)
    op_fc = OperatorEnd(builder)

    SoftmaxOptionsStart(builder)
    SoftmaxOptionsAddBeta(builder, 1.0)
    softmax_options = SoftmaxOptionsEnd(builder)

    sm_inputs = int_vector(builder, OperatorStartInputsVector, [3])
    sm_outputs = int_vector(builder, OperatorStartOutputsVector, [4])
    OperatorStart(builder)
    OperatorAddOpcodeIndex(builder, 1)
    OperatorAddInputs(builder, sm_inputs)
    OperatorAddOutputs(builder, sm_outputs)
    OperatorAddBuiltinOptionsType(builder, BuiltinOptions.SoftmaxOptions)
    OperatorAddBuiltinOptions(builder, softmax_options)
    op_softmax = OperatorEnd(builder)

    operators_vec = offset_vector(builder, SubGraphStartOperatorsVector, [op_fc, op_softmax])

    sg_inputs = int_vector(builder, SubGraphStartInputsVector, [0])
    sg_outputs = int_vector(builder, SubGraphStartOutputsVector, [4])
    sg_name = builder.CreateString("notification_classifier")
    SubGraphStart(builder)
    SubGraphAddTensors(builder, tensors_vec)
    SubGraphAddInputs(builder, sg_inputs)
    SubGraphAddOutputs(builder, sg_outputs)
    SubGraphAddOperators(builder, operators_vec)
    SubGraphAddName(builder, sg_name)
    subgraph = SubGraphEnd(builder)
    subgraphs_vec = offset_vector(builder, ModelStartSubgraphsVector, [subgraph])

    OperatorCodeStart(builder)
    OperatorCodeAddDeprecatedBuiltinCode(builder, BuiltinOperator.FULLY_CONNECTED)
    OperatorCodeAddBuiltinCode(builder, BuiltinOperator.FULLY_CONNECTED)
    OperatorCodeAddVersion(builder, 1)
    opcode_fc = OperatorCodeEnd(builder)

    OperatorCodeStart(builder)
    OperatorCodeAddDeprecatedBuiltinCode(builder, BuiltinOperator.SOFTMAX)
    OperatorCodeAddBuiltinCode(builder, BuiltinOperator.SOFTMAX)
    OperatorCodeAddVersion(builder, 1)
    opcode_softmax = OperatorCodeEnd(builder)

    opcodes_vec = offset_vector(builder, ModelStartOperatorCodesVector, [opcode_fc, opcode_softmax])

    description_off = builder.CreateString(description)
    ModelStart(builder)
    ModelAddVersion(builder, 3)
    ModelAddOperatorCodes(builder, opcodes_vec)
    ModelAddSubgraphs(builder, subgraphs_vec)
    ModelAddDescription(builder, description_off)
    ModelAddBuffers(builder, buffers_vec)
    model = ModelEnd(builder)

    builder.Finish(model, file_identifier=b"TFL3")
    return bytes(builder.Output())


PHASE2_RULE_ENGINE_ACCURACY = 94.68  # same number tools/evaluate_ml_model_on_phase2_corpus.py compares against


def main():
    print(f"Loading {TSV_PATH} ...")
    X, y, template_ids, texts, sources = load_dataset()
    source_counts = {s: int((sources == s).sum()) for s in sorted(set(sources))}
    print(f"  {len(y)} examples, {len(set(template_ids))} templates, {X.shape[1]}-dim features, "
          f"sources={source_counts}")
    sample_weight = source_balanced_weights(sources)

    # ---- Template-level held-out split (the honest generalization estimate: entire phrasing
    #      patterns excluded from training, not just row-level substitution variants). ----
    train_mask, test_mask = template_level_split(template_ids, y)
    X_train_tpl, y_train_tpl = X[train_mask], y[train_mask]
    X_test_tpl, y_test_tpl = X[test_mask], y[test_mask]
    weight_train_tpl = sample_weight[train_mask]

    # ---- Row-level held-out split (kept only as a secondary reference point -- see the
    #      caveat printed with it below; it's the number that looked good previously while
    #      genuine generalization did not). ----
    X_train_row, X_test_row, y_train_row, y_test_row, weight_train_row, _ = train_test_split(
        X, y, sample_weight, test_size=0.2, stratify=y, random_state=RANDOM_STATE
    )

    print("Selecting (C, class_weight) via 5-fold template-grouped CV (source-weighted)...")
    (best_C, best_class_weight), best_cv_score = pick_best_hyperparams(X, y, template_ids, sample_weight)
    print(f"  -> chosen C={best_C}, class_weight={best_class_weight} "
          f"(mean CV accuracy {best_cv_score * 100:.2f}%)")

    # Template-level held-out accuracy -- computed FIRST and reported first: this is the
    # number that answers "does this model generalize," which is the whole point of this
    # round of work, so it must not be buried under a training-set-fit number.
    model_tpl = train_logreg(X_train_tpl, y_train_tpl, best_C, best_class_weight, weight_train_tpl)
    pred_tpl = model_tpl.predict(X_test_tpl)
    acc_tpl = float((pred_tpl == y_test_tpl).mean())

    model_row = train_logreg(X_train_row, y_train_row, best_C, best_class_weight, weight_train_row)
    pred_row = model_row.predict(X_test_row)
    acc_row = float((pred_row == y_test_row).mean())

    final_model = train_logreg(X, y, best_C, best_class_weight, sample_weight)
    final_train_pred = final_model.predict(X)
    final_train_acc = float((final_train_pred == y).mean())

    report_lines = []
    report_lines.append("=== ActionBox on-device ML classifier: training report ===")
    report_lines.append("")
    report_lines.append("HEADLINE FINDING -- template-level held-out accuracy (entire sentence")
    report_lines.append("patterns never seen during training; this is the real generalization")
    report_lines.append("estimate, not the row-level or training-set numbers further down):")
    report_lines.append(f"  {acc_tpl * 100:.2f}%   (n={len(y_test_tpl)} held-out examples)")
    report_lines.append(f"  Rule engine (Phase 2, same-style comparison): {PHASE2_RULE_ENGINE_ACCURACY:.2f}%")
    diff = acc_tpl * 100 - PHASE2_RULE_ENGINE_ACCURACY
    if diff >= 0:
        report_lines.append(f"  -> ML model is {diff:+.2f} percentage points AHEAD of the rule engine.")
    else:
        report_lines.append(f"  -> ML model is {diff:+.2f} percentage points BEHIND the rule engine. "
                             f"Say so plainly: on unseen phrasing, the rule engine still wins.")
    report_lines.append("")
    report_lines.append("-- By category (template-level held-out) --")
    report_lines.append(per_category_report(y_test_tpl, pred_tpl))
    report_lines.append("")
    report_lines.append(f"Dataset: {len(y)} examples from {len(set(template_ids))} distinct "
                         f"sentence structures ({source_counts.get('synthetic', 0)} rows from "
                         f"SyntheticNotificationCorpus's template+substitution style, "
                         f"{source_counts.get('diverse', 0)} rows from DiverseNotificationCorpus's "
                         f"independently-written sentences, {source_counts.get('real', 0)} rows "
                         f"from RealDatasetCorpus's public-dataset text -- SMS Spam Collection, "
                         f"SpamAssassin, Enron, Schema-Guided Dialogue, see that file's doc)")
    report_lines.append("Real ON-DEVICE CORRECTED examples included: 0 (none available in this "
                         "sandbox -- no connected device/user data; not to be confused with "
                         "RealDatasetCorpus above, which is real public-dataset TEXT, not real "
                         "corrections from actual ActionBox usage)")
    report_lines.append(f"Feature space: {VECTOR_SIZE}-dim hashed bag-of-words (HashedTextVectorizer)")
    report_lines.append("Model: multinomial logistic regression (= FULLY_CONNECTED + SOFTMAX)")
    report_lines.append(f"Sample weighting: each `source` value (synthetic/diverse/real) contributes "
                         f"equal TOTAL weight during training (see source_balanced_weights), so "
                         f"the templated corpus's much higher row count per structure can't "
                         f"drown out the diverse or real examples")
    report_lines.append(f"Hyperparameters: C={best_C}, class_weight={best_class_weight}, chosen "
                         f"by 5-fold CV grouped by template, source-weighted (mean CV accuracy "
                         f"{best_cv_score * 100:.2f}%)")
    report_lines.append("")
    report_lines.append(f"-- Row-level held-out test set (n={len(y_test_row)}, 80/20 random "
                         f"stratified split) -- SECONDARY REFERENCE ONLY, see caveat --")
    report_lines.append(f"Accuracy: {acc_row * 100:.2f}%")
    report_lines.append(per_category_report(y_test_row, pred_row))
    report_lines.append("CAVEAT: this number still leaks sibling variants of seen templates into")
    report_lines.append("training and reads far higher than genuine generalization allows -- see")
    report_lines.append("the headline finding above, not this, for real-world expectations.")
    report_lines.append("")
    report_lines.append(f"-- Final shipped model (trained on all {len(y)} examples, source-weighted) --")
    report_lines.append(f"Training-set accuracy (not a generalization estimate -- see the headline "
                         f"finding above for that): {final_train_acc * 100:.2f}%")
    report_lines.append(per_category_report(y, final_train_pred))

    report = "\n".join(report_lines)
    print()
    print(report)
    with open(REPORT_PATH, "w") as f:
        f.write(report + "\n")
    print(f"\nWrote {REPORT_PATH}")

    weights = final_model.coef_        # shape (6, VECTOR_SIZE)
    bias = final_model.intercept_      # shape (6,)

    description = (
        f"ActionBox notification classifier -- multinomial logistic regression, "
        f"trained on {len(y)} examples ({len(set(template_ids))} distinct structures), "
        f"template-holdout accuracy {acc_tpl*100:.1f}%"
    )
    tflite_bytes = build_tflite_model(weights, bias, description)
    with open(OUTPUT_PATH, "wb") as f:
        f.write(tflite_bytes)
    print(f"Wrote {len(tflite_bytes)} bytes to {OUTPUT_PATH}")


if __name__ == "__main__":
    sys.exit(main())
