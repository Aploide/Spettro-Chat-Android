package to.eyed.spettro.chat.data.recall

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * On-device text embeddings for semantic recall. Two tiers:
 *
 *  - **"use-1"** — MediaPipe's Universal Sentence Encoder Lite, a ~6 MB
 *    TFLite model downloaded on demand (never bundled) into
 *    `filesDir/models`. Real semantic similarity; runs in a few ms per
 *    chunk on any recent phone.
 *  - **"hash-1"** — a dependency-free fallback: word unigrams plus
 *    character trigrams feature-hashed into 256 dims, L2-normalized.
 *    Lexical rather than semantic, but it makes search-history useful
 *    before (or without) the model download.
 *
 * The active embedder's [modelId] is stamped on every stored vector, so the
 * index knows to re-embed everything once the better model appears.
 */
class EmbeddingService(private val context: Context) {
    companion object {
        const val HASH_MODEL_ID = "hash-1"
        const val USE_MODEL_ID = "use-1"
        const val MODEL_FILE = "universal_sentence_encoder.tflite"
        const val MODEL_SIZE_BYTES = 6_120_274L
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/text_embedder/" +
                "universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"

        private const val HASH_DIM = 256
    }

    private val mutex = Mutex()
    private var embedder: TextEmbedder? = null
    private var embedderBroken = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading

    /** Failure reason of the last download attempt; null after a success. */
    val downloadError = MutableStateFlow<String?>(null)

    /**
     * Fire-and-forget download in the service's own scope, so closing the
     * settings sheet mid-download does not abort it. Progress and failure
     * surface through [downloading] and [downloadError].
     */
    fun startDownload() {
        scope.launch { downloadError.value = downloadModel() }
    }

    private val modelFile: File get() = File(File(context.filesDir, "models"), MODEL_FILE)

    /** Whether the downloaded model is present (and not a partial file). */
    fun modelDownloaded(): Boolean = modelFile.length() == MODEL_SIZE_BYTES ||
        (modelFile.exists() && modelFile.length() > 1_000_000 && !File(modelFile.path + ".part").exists())

    /** The id stamped on vectors produced right now. */
    fun modelId(): String = if (modelDownloaded() && !embedderBroken) USE_MODEL_ID else HASH_MODEL_ID

    /**
     * Downloads the USE model. Safe to call repeatedly; no-op when present.
     * Returns null on success, or a short human-readable failure reason.
     */
    suspend fun downloadModel(): String? = withContext(Dispatchers.IO) {
        if (modelDownloaded()) return@withContext null
        if (!_downloading.compareAndSet(expect = false, update = true)) return@withContext null
        try {
            val part = File(modelFile.path + ".part")
            part.parentFile?.mkdirs()
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "download failed (HTTP ${resp.code})"
                part.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
            }
            if (part.length() < 1_000_000) {
                part.delete()
                return@withContext "download failed (truncated file)"
            }
            modelFile.delete()
            if (!part.renameTo(modelFile)) {
                part.delete()
                return@withContext "could not store the model file"
            }
            null
        } catch (e: Exception) {
            File(modelFile.path + ".part").delete()
            e.message?.take(120) ?: "download failed"
        } finally {
            _downloading.value = false
        }
    }

    /**
     * Embeds one text with the best available embedder; never throws. If the
     * TFLite model fails to load (corrupt file, unsupported device) it is
     * marked broken for the process and the hash embedder takes over.
     */
    suspend fun embed(text: String): FloatArray {
        if (modelId() == USE_MODEL_ID) {
            useEmbed(text)?.let { return it }
        }
        return hashEmbed(text)
    }

    private suspend fun useEmbed(text: String): FloatArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val e = embedder ?: run {
                    val bytes = modelFile.readBytes()
                    val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                    val options = TextEmbedder.TextEmbedderOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
                        .build()
                    TextEmbedder.createFromOptions(context, options).also { embedder = it }
                }
                val floats = e.embed(text.take(2_000))
                    .embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                    ?: return@withContext null
                normalize(floats.copyOf())
            } catch (t: Throwable) {
                // Covers UnsatisfiedLinkError and friends, not just Exception:
                // a device the runtime can't load on must fall back, not crash.
                embedderBroken = true
                runCatching { embedder?.close() }
                embedder = null
                null
            }
        }
    }

    // --- The dependency-free fallback ---

    private fun hashEmbed(text: String): FloatArray {
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
