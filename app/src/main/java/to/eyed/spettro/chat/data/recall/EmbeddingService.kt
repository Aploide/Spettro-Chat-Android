package to.eyed.spettro.chat.data.recall

import android.content.Context
import java.io.File
import kotlin.math.sqrt

/**
 * On-device text vectors for recall: word unigrams plus character trigrams
 * feature-hashed into 256 dims, L2-normalized. Lexical rather than semantic,
 * dependency-free, and fast enough to index thousands of chunks in
 * milliseconds on any phone.
 *
 * The [modelId] is stamped on every stored vector, so the index can rebuild
 * if the embedding scheme ever changes.
 */
class EmbeddingService(context: Context) {
    companion object {
        const val HASH_MODEL_ID = "hash-1"

        private const val HASH_DIM = 256
    }

    init {
        // Earlier versions downloaded a TFLite embedding model here; reclaim
        // the space if one is still lying around.
        val models = File(context.filesDir, "models")
        File(models, "universal_sentence_encoder.tflite").delete()
        File(models, "universal_sentence_encoder.tflite.part").delete()
    }

    /** The id stamped on vectors produced right now. */
    fun modelId(): String = HASH_MODEL_ID

    fun embed(text: String): FloatArray {
        val v = FloatArray(HASH_DIM)
        val norm = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        val words = norm.split(' ').filter { it.length > 1 }
        for (w in words) addFeature(v, "w:$w", 1.0f)
        val squashed = norm.replace(" ", "")
        for (i in 0..(squashed.length - 3)) {
            addFeature(v, "g:" + squashed.substring(i, i + 3), 0.35f)
        }
        return normalize(v)
    }

    private fun addFeature(v: FloatArray, feature: String, weight: Float) {
        val h = feature.hashCode()
        val idx = Math.floorMod(h, HASH_DIM)
        val sign = if ((h ushr 31) == 1) -1f else 1f
        v[idx] += sign * weight
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val len = sqrt(sum).toFloat()
        if (len > 0f) for (i in v.indices) v[i] /= len
        return v
    }
}
