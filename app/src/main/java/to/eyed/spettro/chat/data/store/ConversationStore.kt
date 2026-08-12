package to.eyed.spettro.chat.data.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

// Local-only persistence, mirroring the CLI's ~/.spettro/sessions/ layout.
// The backend has no conversation sync API.

@Serializable
data class StoredMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val thinking: String = "",
    val at: Long,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String = "",
    val preview: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val messages: List<StoredMessage> = emptyList(),
) {
    val displayTitle: String get() = title.ifBlank { preview.ifBlank { "New Chat" } }
}

class ConversationStore(context: Context) {
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun newId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching { json.decodeFromString(Conversation.serializer(), f.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        val file = File(dir, "${conversation.id}.json")
        val tmp = File(dir, "${conversation.id}.json.tmp")
        tmp.writeText(json.encodeToString(Conversation.serializer(), conversation))
        tmp.renameTo(file)
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }
}
