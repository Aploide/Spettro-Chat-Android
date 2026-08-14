package to.eyed.spettro.chat.data.store

import android.content.Context
import android.net.Uri
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

@Serializable
data class StoredMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val thinking: String = "",
    val at: Long,
    /** Attached images as data URLs (data:image/jpeg;base64,...). */
    val images: List<String> = emptyList(),
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
    val messages: List<StoredMessage> = emptyList(),
) {
    val displayTitle: String get() = title.ifBlank { preview.ifBlank { "New Chat" } }
}

/**
 * Envelope written by "Export chats" and read back by "Import chats".
 * Conversations use the same schema as the pre-Room per-chat files, so old
 * backups of those files remain readable by hand if it ever matters.
 */
@Serializable
data class ChatExport(
    val app: String = "spettro-chat",
    val version: Int = 1,
    val exportedAt: Long = 0,
    val conversations: List<Conversation> = emptyList(),
)

data class ImportResult(val imported: Int, val skipped: Int)

class ConversationStore(private val context: Context) {
    private val dao = ChatDatabase.build(context).conversations()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val toolsSerializer = ListSerializer(StoredToolRun.serializer())

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

    /** Writes every chat to [uri] as one JSON document; returns the count. */
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = loadAll()
        val payload = ChatExport(exportedAt = System.currentTimeMillis(), conversations = all)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("could not open the selected file")
        out.bufferedWriter().use { it.write(json.encodeToString(ChatExport.serializer(), payload)) }
        all.size
    }

    /**
     * Merges an exported file into the store: unknown chats are added, known
     * ones are replaced only when the file's copy is newer (so importing an
     * old backup never clobbers fresher local history).
     */
    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        ensureMigrated()
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("could not read the selected file")
        val payload = json.decodeFromString(ChatExport.serializer(), text)
        var imported = 0
        var skipped = 0
        payload.conversations.forEach { conv ->
            val existing = if (conv.id.isBlank()) null else dao.updatedAt(conv.id)
            when {
                conv.id.isBlank() -> skipped++
                existing != null && existing >= conv.updatedAt -> skipped++
                else -> {
                    write(conv)
                    imported++
                }
            }
        }
        ImportResult(imported, skipped)
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
        messages = messages.sortedBy { it.message.ord }.map { m ->
            StoredMessage(
                role = m.message.role,
                content = m.message.content,
                thinking = m.message.thinking,
                at = m.message.at,
                images = m.images.sortedBy { it.ord }.map { it.dataUrl },
                tools = m.message.toolsJson.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { json.decodeFromString(toolsSerializer, it) }.getOrNull() }
                    ?: emptyList(),
            )
        },
    )
}
