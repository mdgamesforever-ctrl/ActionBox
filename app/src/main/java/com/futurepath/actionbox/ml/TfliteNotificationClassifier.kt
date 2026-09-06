package com.futurepath.actionbox.ml

import android.content.Context
import android.util.Log
import com.futurepath.actionbox.classification.ClassificationResult
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.classification.HashedTextVectorizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device (TensorFlow Lite), fully offline alternative/complementary classification path to
 * the rule-based [com.futurepath.actionbox.classification.NotificationClassifier] — no network
 * call is made or possible; everything runs from a model file bundled in the APK's assets.
 *
 * The model currently shipped ([MODEL_ASSET_PATH]) is a **placeholder**: its FULLY_CONNECTED
 * weights are untrained deterministic noise (see tools/build_stub_tflite_model.py), not a
 * learned classifier. It exists so the runtime integration — dependency, asset packaging,
 * tensor plumbing, error handling — compiles and runs end to end before a real trained model
 * is ready. Its predictions are not meaningful yet and are not surfaced in the UI; see
 * [com.futurepath.actionbox.data.NotificationRepository] for how its output is currently
 * recorded (into `mlClassifiedState`/`mlConfidence`) purely for later comparison against the
 * rule-based classifier and user corrections, the same way `correctedState` already is.
 *
 * Input/output contract a real trained model must keep (or this class and
 * [HashedTextVectorizer] must be updated to match):
 *  - Input: float32 tensor, shape `[1, HashedTextVectorizer.VECTOR_SIZE]` — the hashed
 *    bag-of-words vector for the notification's normalized text.
 *  - Output: float32 tensor, shape `[1, 6]` — a softmax probability per [ClassifiedState],
 *    in [ClassifiedState.values()] declaration order (ACTION, REPLY, WAITING, DEADLINE, FYI,
 *    NOISE).
 *
 * Construction never throws: if the model asset is missing, corrupt, or the native TFLite
 * library fails to load on a given device, this degrades to a no-op (every [classify] call
 * returns null) rather than crashing the notification listener service that owns the caller.
 */
class TfliteNotificationClassifier(context: Context) {

    private val mutex = Mutex()
    private val interpreter: Interpreter? = try {
        Interpreter(loadModelFile(context))
    } catch (e: Exception) {
        Log.w(TAG, "On-device ML model unavailable; ML classification disabled for this session.", e)
        null
    }

    /** Null if the model failed to load, or if inference itself fails for this input. */
    suspend fun classify(normalizedText: String): ClassificationResult? {
        val interpreter = interpreter ?: return null
        val input = arrayOf(HashedTextVectorizer.vectorize(normalizedText))
        val output = Array(1) { FloatArray(CATEGORY_ORDER.size) }

        return try {
            // Interpreter.run() is not safe to call concurrently on the same instance; capture()
            // can run from multiple coroutines for near-simultaneous notifications.
            mutex.withLock { interpreter.run(input, output) }
            val probabilities = output[0]
            val winnerIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: return null
            ClassificationResult(
                state = CATEGORY_ORDER[winnerIndex],
                summary = null,
                date = null,
                confidence = (probabilities[winnerIndex] * 100).toInt().coerceIn(0, 100)
            )
        } catch (e: Exception) {
            Log.w(TAG, "On-device ML inference failed", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_ASSET_PATH)
        return FileInputStream(assetFd.fileDescriptor).use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
        }
    }

    companion object {
        private const val TAG = "TfliteClassifier"
        private const val MODEL_ASSET_PATH = "models/notification_classifier_stub.tflite"

        // ClassifiedState.values() is already ACTION, REPLY, WAITING, DEADLINE, FYI, NOISE —
        // reusing it directly (rather than a hand-written list) keeps this in sync with the
        // enum automatically if a category is ever added/reordered there.
        private val CATEGORY_ORDER = ClassifiedState.values()
    }
}
