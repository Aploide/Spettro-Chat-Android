package to.eyed.spettro.chat.data.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

// Local-only persistence in Room (conversations.db); the backend has no
// conversation sync API. Cross-device transfer goes through the JSON
// export/import below instead.

/** A record of one tool the assistant used while producing a message. */
@Serializable
data class StoredToolRun(
    val name: String,
    val label: String,
    val ok: Boolean = true,
    /** The tool's response, kept so the user can inspect it later. */
    val output: String = "",
)

/**
 * A document attached to a user message, stored as its extracted text
 * (the original file never leaves the sending app's provider).
 */
@Serializable
data class StoredFile(
    val name: String,
    val text: String,
)

@Serializable
data class StoredMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val thinking: String = "",
    val at: Long,
    /** Attached images as data URLs (data:image/jpeg;base64,...). */
    val images: List<String> = emptyList(),
    /** Attached documents (PDF/text), as extracted text. */
    val files: List<StoredFile> = emptyList(),
    /** Tools the assistant ran during this turn, in order. */
    val tools: List<StoredToolRun> = emptyList(),
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
    /** Active skill id; defaulted so pre-skill exports keep importing. */
    val skillId: String? = null,
    val messages: List<StoredMessage> = emptyList(),
) {
    val displayTitle: String get() = title.ifBlank { preview.ifBlank { "New Chat" } }
}

data class ImportResult(val imported: Int, val skipped: Int)

class ConversationStore(private val context: Context, private val dao: ConversationDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val toolsSerializer = ListSerializer(StoredToolRun.serializer())
    private val filesSerializer = ListSerializer(StoredFile.serializer())

    private val migration = Mutex()
    private var migrated = false

    fun newId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** One-time move of the legacy one-file-per-chat layout into Room. */
    private suspend fun ensureMigrated() = migration.withLock {
        if (migrated) return@withLock
        val legacy = File(context.filesDir, "conversations")
        if (legacy.isDirectory) {
            (legacy.listFiles { f -> f.extension == "json" } ?: emptyArray()).forEach { f ->
                runCatching { json.decodeFromString(Conversation.serializer(), f.readText()) }
                    .getOrNull()
                    ?.let { write(it) }
            }
            legacy.deleteRecursively()
        }
        migrated = true
    }

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        ensureMigrated()
        dao.loadAll().map { it.toDomain() }
    }

    suspend fun save(conversation: Conversation): Unit = withContext(Dispatchers.IO) {
        ensureMigrated()
        write(conversation)
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        ensureMigrated()
        dao.delete(id)
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        ensureMigrated()
        dao.deleteAll()
    }

    /**
     * Merges exported chats into the store: unknown chats are added, known
     * ones are replaced only when the imported copy is newer (so importing an
     * old backup never clobbers fresher local history). File IO lives in
     * BackupManager, which bundles chats with the rest of the app's data.
     */
    suspend fun merge(imported: List<Conversation>): ImportResult = withContext(Dispatchers.IO) {
        ensureMigrated()
        var added = 0
        var skipped = 0
        imported.forEach { conv ->
            val existing = if (conv.id.isBlank()) null else dao.updatedAt(conv.id)
            when {
                conv.id.isBlank() -> skipped++
                existing != null && existing >= conv.updatedAt -> skipped++
                else -> {
                    write(conv)
                    added++
                }
            }
        }
        ImportResult(added, skipped)
    }

    private suspend fun write(conversation: Conversation) {
        dao.replace(
            ConversationEntity(
                id = conversation.id,
                title = conversation.title,
                preview = conversation.preview,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
                pinned = conversation.pinned,
                archived = conversation.archived,
                skillId = conversation.skillId,
            ),
            conversation.messages.mapIndexed { ord, m ->
                MessageEntity(
                    conversationId = conversation.id,
                    ord = ord,
                    role = m.role,
                    content = m.content,
                    thinking = m.thinking,
                    at = m.at,
                    toolsJson = if (m.tools.isEmpty()) "" else json.encodeToString(toolsSerializer, m.tools),
                    filesJson = if (m.files.isEmpty()) "" else json.encodeToString(filesSerializer, m.files),
                )
            },
            conversation.messages.map { it.images },
        )
    }

    private fun ConversationWithMessages.toDomain() = Conversation(
        id = conversation.id,
        title = conversation.title,
        preview = conversation.preview,
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
        pinned = conversation.pinned,
        archived = conversation.archived,
        skillId = conversation.skillId,
        messages = messages.sortedBy { it.message.ord }.map { m ->
            StoredMessage(
                role = m.message.role,
                content = m.message.content,
                thinking = m.message.thinking,
                at = m.message.at,
                images = m.images.sortedBy { it.ord }.map { it.dataUrl },
                files = m.message.filesJson.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { json.decodeFromString(filesSerializer, it) }.getOrNull() }
                    ?: emptyList(),
                tools = m.message.toolsJson.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { json.decodeFromString(toolsSerializer, it) }.getOrNull() }
                    ?: emptyList(),
            )
        },
    )
}
