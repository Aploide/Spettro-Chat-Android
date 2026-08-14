package to.eyed.spettro.chat.data.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.eyed.spettro.chat.data.store.MemoryDao
import to.eyed.spettro.chat.data.store.MemoryEntity
import java.security.MessageDigest

/** One remembered fact, shown in the Settings editor. */
data class MemoryFact(
    val id: String,
    val text: String,
    val addedAt: Long,
    val usedAt: Long,
)

/** What save() did with a fact (mirrors the CLI's SaveOutcome). */
sealed interface MemorySaveOutcome {
    data object New : MemorySaveOutcome
    /** Exact (normalized) match existed; its used date was bumped instead. */
    data class Duplicate(val existing: String) : MemorySaveOutcome
    /** A near-duplicate was replaced by the new wording. */
    data class Superseded(val old: String) : MemorySaveOutcome
    data class Invalid(val reason: String) : MemorySaveOutcome
}

/**
 * Persistent cross-chat memory, ported from the CLI's internal/memory
 * package: short single-line facts, exact duplicates bump a used date
 * instead of piling up, near-duplicates (high token overlap or same leading
 * phrase) supersede the old wording, and injection is recently-used-first
 * under a byte cap so the stalest facts are the ones dropped. The CLI routes
 * near-duplicates to a review inbox; on the phone the Settings editor is the
 * review surface, so they replace directly. Editable by both the user
 * (Settings → Memory) and the model (save-memory / forget-memory).
 */
class MemoryStore(private val dao: MemoryDao) {
    companion object {
        /** Caps a single saved fact (CLI: maxFactLen). */
        const val MAX_FACT_LEN = 500

        /** Caps how much memory is injected into context (CLI: maxFileBytes). */
        const val MAX_CONTEXT_BYTES = 8 * 1024

        /** Jaccard token-overlap threshold for near-duplicates. */
        private const val NEAR_DUP_OVERLAP = 0.8
    }

    val all: Flow<List<MemoryFact>> = dao.all().map { list -> list.map { it.toDomain() } }

    suspend fun allOnce(): List<MemoryFact> = dao.allOnce().map { it.toDomain() }

    /** Validates and stores one fact, deduplicating like the CLI's Save. */
    suspend fun save(fact: String): MemorySaveOutcome {
        val text = fact.trim()
        validate(text)?.let { return MemorySaveOutcome.Invalid(it) }
        val norm = normalize(text)
        val now = System.currentTimeMillis()
        val existing = dao.allOnce()
        existing.firstOrNull { normalize(it.text) == norm }?.let {
            dao.upsert(it.copy(usedAt = now))
            return MemorySaveOutcome.Duplicate(it.text)
        }
        existing.firstOrNull { nearDuplicate(it.text, text) }?.let { near ->
            dao.delete(near.id)
            dao.upsert(MemoryEntity(id = factId(text), text = text, addedAt = near.addedAt, usedAt = now))
            return MemorySaveOutcome.Superseded(near.text)
        }
        dao.upsert(MemoryEntity(id = factId(text), text = text, addedAt = now, usedAt = now))
        return MemorySaveOutcome.New
    }

    /**
     * Deletes every fact whose normalized text equals — or, failing that,
     * contains — the query. Returns the removed texts (empty = no match).
     */
    suspend fun forget(query: String): List<String> {
        val norm = normalize(query)
        if (norm.isBlank()) return emptyList()
        val existing = dao.allOnce()
        val exact = existing.filter { normalize(it.text) == norm }
        val hits = exact.ifEmpty { existing.filter { normalize(it.text).contains(norm) } }
        hits.forEach { dao.delete(it.id) }
        return hits.map { it.text }
    }

    /** Settings editor: rewrite one fact in place, keeping its added date. */
    suspend fun update(id: String, newText: String): MemorySaveOutcome {
        val text = newText.trim()
        validate(text)?.let { return MemorySaveOutcome.Invalid(it) }
        val current = dao.allOnce().firstOrNull { it.id == id } ?: return save(text)
        val norm = normalize(text)
        // Editing into a wording that already exists elsewhere just merges.
        dao.allOnce().firstOrNull { it.id != id && normalize(it.text) == norm }?.let {
            dao.delete(id)
            dao.upsert(it.copy(usedAt = System.currentTimeMillis()))
            return MemorySaveOutcome.Duplicate(it.text)
        }
        dao.delete(id)
        dao.upsert(
            MemoryEntity(
                id = factId(text),
                text = text,
                addedAt = current.addedAt,
                usedAt = System.currentTimeMillis(),
            ),
        )
        return MemorySaveOutcome.New
    }

    /**
     * Backup restore: keeps the fact's original dates. Exact duplicates just
     * take the newer used date; near-duplicate routing is skipped — a backup
     * is already-reviewed data, like the CLI's SaveApproved.
     */
    suspend fun importFact(text: String, addedAt: Long, usedAt: Long): Boolean {
        val fact = text.trim()
        if (validate(fact) != null) return false
        val norm = normalize(fact)
        val now = System.currentTimeMillis()
        dao.allOnce().firstOrNull { normalize(it.text) == norm }?.let {
            dao.upsert(it.copy(usedAt = maxOf(it.usedAt, usedAt)))
            return false
        }
        dao.upsert(
            MemoryEntity(
                id = factId(fact),
                text = fact,
                addedAt = if (addedAt > 0) addedAt else now,
                usedAt = if (usedAt > 0) usedAt else now,
            ),
        )
        return true
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * The system-prompt section: recently-used-first bullets under the byte
     * cap, metadata stripped — the CLI's Load(). Empty string when nothing
     * is remembered.
     */
    suspend fun contextSection(): String {
        val facts = dao.allOnce() // already usedAt DESC, addedAt DESC
        if (facts.isEmpty()) return ""
        val sb = StringBuilder(
            "\n\n# Memory\nFacts and preferences saved in earlier chats. Honor them unless the user says otherwise.\n",
        )
        for (fact in facts) {
            val line = "- ${fact.text}\n"
            if (sb.length + line.length > MAX_CONTEXT_BYTES) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    private fun validate(text: String): String? = when {
        text.isEmpty() -> "the fact is empty"
        text.length > MAX_FACT_LEN -> "the fact is too long (${text.length} chars, max $MAX_FACT_LEN) — keep memories short"
        text.contains('\n') || text.contains('\r') -> "the fact must be a single line"
        else -> null
    }

    private fun MemoryEntity.toDomain() = MemoryFact(id, text, addedAt, usedAt)

    // --- Text matching, ported from the CLI ---

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun factId(text: String): String {
        val sum = MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray())
        return "m-" + sum.take(3).joinToString("") { "%02x".format(it) }
    }

    /** Jaccard similarity of the normalized token sets. */
    private fun tokenOverlap(a: String, b: String): Double {
        val aTokens = normalize(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val bTokens = normalize(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val inter = aTokens.intersect(bTokens).size
        return inter.toDouble() / (aTokens.size + bTokens.size - inter)
    }

    /**
     * High token overlap, or the same three leading tokens — the same
     * subject with a different tail ("prefers tabs" vs "prefers spaces").
     */
    private fun nearDuplicate(a: String, b: String): Boolean {
        if (tokenOverlap(a, b) >= NEAR_DUP_OVERLAP) return true
        val aTokens = normalize(a).split(' ')
        val bTokens = normalize(b).split(' ')
        return aTokens.size >= 3 && bTokens.size >= 3 && aTokens.take(3) == bTokens.take(3)
    }
}
