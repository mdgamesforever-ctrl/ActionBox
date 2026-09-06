#!/usr/bin/env python3
"""
Hand-builds a minimal, VALID .tflite FlatBuffer for ActionBox's on-device notification
classifier stub -- without needing the full TensorFlow package (which is far too heavy to
install just to produce a placeholder). Uses only the `flatbuffers` runtime plus the `tflite`
package (Python bindings generated from the same schema.fbs TensorFlow Lite itself uses), so
the output is a genuinely well-formed TFLite model the real org.tensorflow:tensorflow-lite
Android Interpreter can load and run -- it just hasn't been trained on anything yet.

Graph: input[1, VECTOR_SIZE] float32 -> FULLY_CONNECTED(weights[6, VECTOR_SIZE], bias[6])
       -> SOFTMAX -> output[1, 6] float32

Weights are small deterministic pseudo-random noise (seeded) purely so the op actually
exercises real multiply-accumulate math end to end; they encode no learned signal. Replace
this file with a real Model-Maker/Keras-trained export once one exists, keeping the same
input/output contract (or updating HashedTextVectorizer.VECTOR_SIZE and this script to match).
"""
import numpy as np
import flatbuffers

# NOTE: tflite/__init__.py does `from tflite.Buffer import *` for every submodule, which
# re-exports each submodule's same-named class (Buffer, Tensor, Model, ...) into the `tflite`
# package namespace -- and in doing so overwrites the package's `tflite.Buffer` attribute
# (originally the submodule itself) with the class object. So `import tflite.Buffer as Buffer`
# silently resolves to the *class*, not the module, and its free functions (BufferStart etc.)
# appear missing. Importing the functions directly from the submodule sidesteps this, since
# that goes through sys.modules['tflite.Buffer'] rather than the tflite package's attributes.
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

VECTOR_SIZE = 128   # must match HashedTextVectorizer.VECTOR_SIZE in the Kotlin source
NUM_CLASSES = 6     # must match ClassifiedState.values().size, in enum declaration order:
                     # ACTION, REPLY, WAITING, DEADLINE, FYI, NOISE
SEED = 1234
OUTPUT_PATH = "app/src/main/assets/models/notification_classifier_stub.tflite"


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
    data_off = None
    if data_bytes is not None:
        data_off = builder.CreateByteVector(data_bytes)
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


def build_model():
    rng = np.random.default_rng(SEED)
    weights = (rng.standard_normal((NUM_CLASSES, VECTOR_SIZE)) * 0.05).astype("<f4")
    bias = np.zeros((NUM_CLASSES,), dtype="<f4")

    builder = flatbuffers.Builder(1024)

    # ---- Buffers: index 0 must be the empty/no-data buffer by TFLite convention. ----
    buf_empty = make_buffer(builder, None)
    buf_weights = make_buffer(builder, weights.tobytes())
    buf_bias = make_buffer(builder, bias.tobytes())
    buf_fc_out = make_buffer(builder, None)
    buf_softmax_out = make_buffer(builder, None)
    buffers_vec = offset_vector(
        builder, ModelStartBuffersVector,
        [buf_empty, buf_weights, buf_bias, buf_fc_out, buf_softmax_out]
    )

    # ---- Tensors ----
    t_input = make_tensor(builder, [1, VECTOR_SIZE], TensorType.FLOAT32, 0, "input_vector")
    t_weights = make_tensor(builder, [NUM_CLASSES, VECTOR_SIZE], TensorType.FLOAT32, 1, "fc_weights")
    t_bias = make_tensor(builder, [NUM_CLASSES], TensorType.FLOAT32, 2, "fc_bias")
    t_fc_out = make_tensor(builder, [1, NUM_CLASSES], TensorType.FLOAT32, 3, "fc_output")
    t_output = make_tensor(builder, [1, NUM_CLASSES], TensorType.FLOAT32, 4, "category_probabilities")
    tensors_vec = offset_vector(
        builder, SubGraphStartTensorsVector,
        [t_input, t_weights, t_bias, t_fc_out, t_output]
    )

    # ---- Operators ----
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

    # ---- SubGraph ----
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

    # ---- OperatorCodes ----
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

    # ---- Model ----
    description = builder.CreateString(
        "ActionBox notification classifier STUB - untrained placeholder weights"
    )
    ModelStart(builder)
    ModelAddVersion(builder, 3)
    ModelAddOperatorCodes(builder, opcodes_vec)
    ModelAddSubgraphs(builder, subgraphs_vec)
    ModelAddDescription(builder, description)
    ModelAddBuffers(builder, buffers_vec)
    model = ModelEnd(builder)

    builder.Finish(model, file_identifier=b"TFL3")
    return builder.Output()


if __name__ == "__main__":
    import os
    data = build_model()
    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "wb") as f:
        f.write(bytes(data))
    print(f"Wrote {len(data)} bytes to {OUTPUT_PATH}")
