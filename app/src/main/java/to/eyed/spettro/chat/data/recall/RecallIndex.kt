package to.eyed.spettro.chat.data.recall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.eyed.spettro.chat.data.memory.MemoryStore
import to.eyed.spettro.chat.data.store.ConversationStore
import to.eyed.spettro.chat.data.store.EmbeddingDao
import to.eyed.spettro.chat.data.store.EmbeddingEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One search hit, ready to be formatted for the model. */
data class RecallHit(
    val kind: String, // "memory" | "chat"
    val text: String,
    val at: Long,
    val conversationId: String?,
    val conversationTitle: String?,
    val score: Double,
)

/**
 * The semantic index over memories and past conversations, and the search
 * behind the `search-history` tool. Chunks are content-addressed (the owner
 * key embeds a hash of the chunk text), so indexing is an incremental set
 * difference: new or changed chunks are embedded, chunks whose source is
 * gone are dropped, and untouched chunks cost nothing. Phone-scale corpora
 * (thousands of chunks) brute-force cosine in milliseconds, so there is no
 * ANN structure to maintain.
 */
class RecallIndex(
    private val dao: EmbeddingDao,
    private val embeddings: EmbeddingService,
    private val conversations: ConversationStore,
    private val memory: MemoryStore,
) {
    companion object {
        const val KIND_MEMORY = "memory"
        const val KIND_CHAT = "chat"

        /** Target chunk size; messages are split on paragraph boundaries. */
        private const val CHUNK_CHARS = 700

        /** Ignore trivially short messages ("ok", "thanks"). */
        private const val MIN_CHARS = 24

        /** Per-call cap so one indexing pass never runs away. */
        private const val MAX_EMBED_BATCH = 512

        /** Similarity floor below which a hit is noise, per embedder. */
        private const val MIN_SCORE_USE = 0.30
        private const val MIN_SCORE_HASH = 0.12

        /** At most this many hits from the same conversation. */
        private const val PER_CONVERSATION_CAP = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexMutex = Mutex()

    /** Skipped from search results: the chat the question was asked in. */
    @Volatile
    var activeConversationProvider: () -> String? = { null }

    /** Kicks an incremental catch-up in the background (debounced by the mutex). */
    fun scheduleCatchUp(delayMs: Long = 3_000) {
        scope.launch {
            delay(delayMs)
            runCatching { ensureIndexed() }
        }
    }

    /** Catch up shortly after each emission (wired to finished engine turns). */
    fun bindTo(events: kotlinx.coroutines.flow.Flow<*>) {
        scope.launch { events.collect { scheduleCatchUp() } }
    }

    /**
     * Brings the index up to date with the store. Incremental and idempotent;
     * safe to call before every search.
     */
    suspend fun ensureIndexed() {
        indexMutex.withLock {
            val modelId = embeddings.modelId()
            // A better embedder appeared (or the model file vanished):
            // vectors from other embedders are not comparable — rebuild them.
            if (dao.countNotFromModel(modelId) > 0) dao.deleteNotFromModel(modelId)

            val wanted = LinkedHashMap<String, EmbeddingEntity>() // ownerKey → row-to-be, vector empty
            for (fact in memory.allOnce()) {
                val key = "mem:${fact.id}:${hash(fact.text)}"
                wanted[key] = EmbeddingEntity(
                    kind = KIND_MEMORY, ownerKey = key, conversationId = null,
                    text = fact.text, at = fact.addedAt, model = modelId, vector = ByteArray(0),
                )
            }
            for (conv in conversations.loadAll()) {
                conv.messages.forEachIndexed { ord, msg ->
                    if (msg.role != "user" && msg.role != "assistant") return@forEachIndexed
                    chunk(msg.content).forEachIndexed { ci, piece ->
                        val key = "c:${conv.id}:$ord:$ci:${hash(piece)}"
                        wanted[key] = EmbeddingEntity(
                            kind = KIND_CHAT, ownerKey = key, conversationId = conv.id,
                            text = piece, at = msg.at, model = modelId, vector = ByteArray(0),
                        )
                    }
                }
            }

            val existing = dao.ownerKeys().toHashSet()
            val stale = existing - wanted.keys
            if (stale.isNotEmpty()) stale.chunked(500).forEach { dao.deleteByOwnerKeys(it) }

            val missing = wanted.values.filter { it.ownerKey !in existing }.take(MAX_EMBED_BATCH)
            if (missing.isNotEmpty()) {
                missing.chunked(64).forEach { batch ->
                    dao.insert(batch.map { it.copy(vector = toBytes(embeddings.embed(it.text))) })
                }
            }
        }
    }

    /** How many chunks are indexed right now (for the settings surface). */
    suspend fun indexedCount(): Int = dao.count()

    suspend fun clear() = dao.deleteAll()

    /**
     * Semantic search over the index. [scope] is "all", "chats", or
     * "memories". Returns ranked hits above the noise floor, at most
     * [PER_CONVERSATION_CAP] per conversation, the active chat excluded
     * (its content is already in context).
     */
    suspend fun search(query: String, scope: String, maxResults: Int): List<RecallHit> {
        ensureIndexed()
        val modelId = embeddings.modelId()
        val qv = embeddings.embed(query)
        val qTokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val minScore = if (modelId == EmbeddingService.USE_MODEL_ID) MIN_SCORE_USE else MIN_SCORE_HASH
        val activeId = activeConversationProvider()
        val titles = conversations.loadAll().associate { it.id to it.displayTitle }

        val hits = dao.byModel(modelId).asSequence()
            .filter { row ->
                when (scope) {
                    "memories" -> row.kind == KIND_MEMORY
                    "chats" -> row.kind == KIND_CHAT
                    else -> true
                }
            }
            .filter { it.conversationId == null || it.conversationId != activeId }
            .map { row ->
                val lower = row.text.lowercase()
                val overlap = if (qTokens.isEmpty()) 0.0 else {
                    qTokens.count { it in lower }.toDouble() / qTokens.size
                }
                // Hybrid score: cosine carries it; literal keyword overlap
                // breaks ties toward chunks that name what was asked about.
                val score = cosine(qv, fromBytes(row.vector)) + 0.15 * overlap
                RecallHit(
                    kind = row.kind,
                    text = row.text,
                    at = row.at,
                    conversationId = row.conversationId,
                    conversationTitle = row.conversationId?.let { titles[it] },
                    score = score,
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .toList()

        val perConv = HashMap<String, Int>()
        val out = ArrayList<RecallHit>(maxResults)
        for (hit in hits) {
            val convKey = hit.conversationId ?: "mem"
            if (hit.conversationId != null) {
                val n = perConv.getOrDefault(convKey, 0)
                if (n >= PER_CONVERSATION_CAP) continue
                perConv[convKey] = n + 1
            }
            out += hit
            if (out.size >= maxResults) break
        }
        return out
    }

    /** Formats hits as the search-history tool's output. */
    fun format(query: String, hits: List<RecallHit>): String {
        if (hits.isEmpty()) {
            return "No past conversations or memories match \"$query\". " +
                "The user may not have discussed this before, or it was in a deleted or temporary chat."
        }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sb = StringBuilder("${hits.size} match(es) from the user's history, most relevant first:\n")
        for (hit in hits) {
            val date = dateFmt.format(Date(hit.at))
            when (hit.kind) {
                KIND_MEMORY -> sb.append("\n[memory, saved $date] ${hit.text}\n")
                else -> sb.append(
                    "\n[chat \"${hit.conversationTitle ?: "Untitled"}\", $date]\n${hit.text.trim()}\n",
                )
            }
        }
        sb.append(
            "\nUse what is relevant and refer to it naturally (\"as we discussed…\"). " +
                "Snippets are fragments; do not assume they are complete.",
        )
        return sb.toString()
    }

    // --- helpers ---

    /** Paragraph-preserving split into ~[CHUNK_CHARS] pieces. */
    private fun chunk(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS) return emptyList()
        if (trimmed.length <= CHUNK_CHARS) return listOf(trimmed)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (para in trimmed.split("\n\n")) {
            if (sb.isNotEmpty() && sb.length + para.length > CHUNK_CHARS) {
                out += sb.toString().trim()
                sb.setLength(0)
            }
            if (para.length > CHUNK_CHARS) {
                // A single huge paragraph: hard-split on the char boundary.
                var i = 0
                while (i < para.length) {
                    out += para.substring(i, minOf(i + CHUNK_CHARS, para.length)).trim()
                    i += CHUNK_CHARS
                }
            } else {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(para)
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.length >= MIN_CHARS }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }

    private fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    private fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(b.size / 4)
        for (i in v.indices) v[i] = buf.getFloat()
        return v
    }

    /** Both vectors are L2-normalized, so the dot product is the cosine. */
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += (a[i] * b[i]).toDouble()
        return dot
    }
}
