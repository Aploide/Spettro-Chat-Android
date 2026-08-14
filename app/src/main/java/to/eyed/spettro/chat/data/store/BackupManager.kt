package to.eyed.spettro.chat.data.store

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.eyed.spettro.chat.data.AppPrefs
import to.eyed.spettro.chat.data.mcp.McpRegistry
import to.eyed.spettro.chat.data.mcp.McpServerConfig
import to.eyed.spettro.chat.data.memory.MemoryStore
import to.eyed.spettro.chat.data.skills.Skill
import to.eyed.spettro.chat.data.skills.SkillsRepository

@Serializable
data class ExportedMemory(val text: String, val addedAt: Long = 0, val usedAt: Long = 0)

@Serializable
data class ExportedSettings(
    val selectedModel: String = "",
    val thinkingLevel: String = "",
    val streamingAnimations: Boolean = true,
    val hapticFeedback: Boolean = true,
    val autoCompact: Boolean = true,
)

/**
 * The whole-app backup envelope. Version 2 is a strict superset of the old
 * chats-only export (app/version/exportedAt/conversations), so files written
 * by earlier builds import cleanly — the extra sections just come up empty —
 * and old builds can still read the chats out of a v2 file thanks to
 * ignoreUnknownKeys. The API key is deliberately never part of a backup:
 * it's a per-device credential, minted on sign-in.
 */
@Serializable
data class SpettroBackup(
    val app: String = "spettro-chat",
    val version: Int = 2,
    val exportedAt: Long = 0,
    val conversations: List<Conversation> = emptyList(),
    /** User-created skills only; bundled ones ship with every install. */
    val skills: List<Skill> = emptyList(),
    val memories: List<ExportedMemory> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    /** "Always allow" consent grants (tool:* / mcp:* keys). */
    val toolConsentAlways: List<String> = emptyList(),
    val settings: ExportedSettings? = null,
)

data class BackupCounts(
    val chats: Int = 0,
    val chatsSkipped: Int = 0,
    val skills: Int = 0,
    val memories: Int = 0,
    val mcpServers: Int = 0,
    val settingsApplied: Boolean = false,
)

/**
 * Exports and restores everything a user would want to carry to another
 * device: chats, custom skills, memory, MCP servers, consent grants, and
 * UI settings — one JSON file.
 */
class BackupManager(
    private val context: Context,
    private val conversations: ConversationStore,
    private val skills: SkillsRepository,
    private val memory: MemoryStore,
    private val mcp: McpRegistry,
    private val prefs: AppPrefs,
    private val settingsChanged: MutableSharedFlow<Unit>,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportTo(uri: Uri): BackupCounts = withContext(Dispatchers.IO) {
        val snapshot = prefs.load()
        val payload = SpettroBackup(
            exportedAt = System.currentTimeMillis(),
            conversations = conversations.loadAll(),
            skills = skills.allOnce().filter { !it.builtin },
            memories = memory.allOnce().map { ExportedMemory(it.text, it.addedAt, it.usedAt) },
            mcpServers = mcp.allServers(),
            toolConsentAlways = prefs.consentAlways().sorted(),
            settings = ExportedSettings(
                selectedModel = snapshot.selectedModel,
                thinkingLevel = snapshot.thinkingLevel,
                streamingAnimations = snapshot.streamingAnimations,
                hapticFeedback = snapshot.hapticFeedback,
                autoCompact = snapshot.autoCompact,
            ),
        )
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("could not open the selected file")
        out.bufferedWriter().use { it.write(json.encodeToString(payload)) }
        BackupCounts(
            chats = payload.conversations.size,
            skills = payload.skills.size,
            memories = payload.memories.size,
            mcpServers = payload.mcpServers.size,
        )
    }

    /**
     * Merges a backup into the app. Nothing local is deleted: chats keep the
     * newer copy, skills skip slug conflicts, memories deduplicate, servers
     * with a known id are replaced by the imported config.
     */
    suspend fun importFrom(uri: Uri): BackupCounts = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("could not read the selected file")
        val payload = json.decodeFromString<SpettroBackup>(text)

        val chatResult = conversations.merge(payload.conversations)

        var skillsImported = 0
        payload.skills.filter { !it.builtin }.forEach { skill ->
            if (skills.save(skill).isSuccess) skillsImported++
        }

        var memoriesImported = 0
        payload.memories.forEach { m ->
            if (memory.importFact(m.text, m.addedAt, m.usedAt)) memoriesImported++
        }

        var serversImported = 0
        if (payload.mcpServers.isNotEmpty()) {
            val known = mcp.allServers().map { it.id }.toSet()
            payload.mcpServers.forEach { server ->
                if (server.id.isBlank() || server.url.isBlank()) return@forEach
                if (server.id in known) mcp.updateServer(server) else mcp.addServer(server)
                serversImported++
            }
        }

        payload.toolConsentAlways.forEach { key ->
            if (key.isNotBlank()) prefs.grantConsentAlways(key)
        }

        val settingsApplied = payload.settings != null
        payload.settings?.let { s ->
            if (s.selectedModel.isNotBlank()) prefs.saveSelectedModel(s.selectedModel)
            if (s.thinkingLevel.isNotBlank()) prefs.saveThinkingLevel(s.thinkingLevel)
            prefs.saveStreamingAnimations(s.streamingAnimations)
            prefs.saveHapticFeedback(s.hapticFeedback)
            prefs.saveAutoCompact(s.autoCompact)
            settingsChanged.tryEmit(Unit)
        }

        BackupCounts(
            chats = chatResult.imported,
            chatsSkipped = chatResult.skipped,
            skills = skillsImported,
            memories = memoriesImported,
            mcpServers = serversImported,
            settingsApplied = settingsApplied,
        )
    }
}
