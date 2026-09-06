package com.futurepath.actionbox.classification

/**
 * Turns normalized notification text into the fixed-size numeric input the on-device ML model
 * ([com.futurepath.actionbox.ml.TfliteNotificationClassifier]) expects: a feature-hashed bag
 * of words, rather than a learned vocabulary/embedding table. Feature hashing needs no vocab
 * file shipped alongside the model (smaller APK, nothing to keep in sync at inference time) at
 * the cost of occasional hash collisions between unrelated words — an acceptable tradeoff for
 * a short-text classifier with only 6 output categories.
 *
 * Pure and Android-free so it can be unit-tested on the JVM like [NotificationClassifier] and
 * [TextNormalizer]. [VECTOR_SIZE] must match the input tensor width baked into the .tflite
 * model (see tools/build_stub_tflite_model.py) — change both together.
 */
object HashedTextVectorizer {

    /** Width of the model's input tensor. Keep in sync with the model file. */
    const val VECTOR_SIZE = 128

    /**
     * One bucket per hashed token, incremented per occurrence and then L1-normalized so a
     * long message doesn't produce a larger-magnitude vector than a short one carrying the
     * same relative word mix — the model should react to *which* words appear, not how many
     * words the notification happens to contain.
     */
    fun vectorize(normalizedText: String): FloatArray {
        val vector = FloatArray(VECTOR_SIZE)
        val tokens = normalizedText.lowercase().split(TOKEN_SPLIT_PATTERN).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return vector

        for (token in tokens) {
            val bucket = (token.hashCode() and Int.MAX_VALUE) % VECTOR_SIZE
            vector[bucket] += 1f
        }
        val tokenCount = tokens.size.toFloat()
        for (i in vector.indices) {
            vector[i] = vector[i] / tokenCount
        }
        return vector
    }

    private val TOKEN_SPLIT_PATTERN = Regex("\\s+")
}
