package to.eyed.spettro.chat.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.store.Conversation
import to.eyed.spettro.chat.data.tools.AskAnswer
import to.eyed.spettro.chat.data.tools.AskForm

/**
 * Thin UI-facing delegate over the app-scoped [to.eyed.spettro.chat.engine.ChatEngine],
 * which owns the agent loop so a turn survives the Activity (and, with the
 * foreground service, the app being backgrounded). Property and method names
 * mirror the engine one-to-one so existing composables stay unchanged.
 * Export/import stay here: they are one-shot UI session concerns.
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {
    private val engine = container.engine
    private val store = container.conversations

    val conversations: StateFlow<List<Conversation>> get() = engine.conversations
    val activeId: StateFlow<String?> get() = engine.activeId
    val stream: StateFlow<StreamState> get() = engine.stream
    val askForm: StateFlow<AskForm?> get() = engine.askForm
    val tempChat: StateFlow<Conversation?> get() = engine.tempChat
    val isTemporary: StateFlow<Boolean> get() = engine.isTemporary

    val activeConversation: Conversation? get() = engine.activeConversation

    // Content shared in from other apps; ChatRoot prefills the composer with it.
    val sharedPayload get() = container.shareInbox.pending
    fun consumeSharedPayload() = container.shareInbox.clear()

    // Consent gate + permission bridge, surfaced for ChatRoot and Settings.
    val consentPending get() = container.consent.pending
    val permissionPending get() = container.permissions.pending
    val alwaysAllowedConsents get() = container.consent.alwaysAllowed
    fun resolveConsent(decision: to.eyed.spettro.chat.engine.ConsentDecision) =
        container.consent.resolve(decision)
    fun resolvePermissions(result: Map<String, Boolean>) = container.permissions.resolve(result)
    fun revokeConsent(key: String) {
        viewModelScope.launch { container.consent.revoke(key) }
    }

    // Skills: bundled + user-created, applied per conversation.
    val skills get() = container.skills.all
    val pendingSkillId get() = engine.pendingSkillId
    fun setConversationSkill(skillId: String?) = engine.setConversationSkill(skillId)
    fun newSkillId(): String = container.skills.newId()

    private val _skillSaveError = MutableStateFlow<String?>(null)
    val skillSaveError: StateFlow<String?> = _skillSaveError.asStateFlow()
    fun clearSkillSaveError() {
        _skillSaveError.value = null
    }
    fun saveSkill(skill: to.eyed.spettro.chat.data.skills.Skill) {
        viewModelScope.launch {
            _skillSaveError.value = container.skills.save(skill).exceptionOrNull()?.message
        }
    }
    fun deleteSkill(id: String) {
        viewModelScope.launch { container.skills.delete(id) }
    }

    // MCP servers, surfaced for the settings sheet.
    val mcpServers get() = container.mcp.servers
    val mcpToolsByServer get() = container.mcp.toolsByServer
    val mcpErrors get() = container.mcp.errors
    fun newMcpId(): String = store.newId()
    fun saveMcpServer(config: to.eyed.spettro.chat.data.mcp.McpServerConfig) {
        viewModelScope.launch {
            if (container.mcp.servers.value.any { it.id == config.id }) container.mcp.updateServer(config)
            else container.mcp.addServer(config)
        }
    }
    fun removeMcpServer(id: String) {
        viewModelScope.launch { container.mcp.removeServer(id) }
    }
    fun setMcpEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { container.mcp.setEnabled(id, enabled) }
    }
    fun refreshMcpTools(id: String) {
        viewModelScope.launch { container.mcp.refreshTools(id) }
    }

    fun newChat() = engine.newChat()
    fun selectChat(id: String) = engine.selectChat(id)
    fun toggleTemporaryChat() = engine.toggleTemporaryChat()
    fun togglePin(id: String) = engine.togglePin(id)
    fun archive(id: String) = engine.archive(id)
    fun restore(id: String) = engine.restore(id)
    fun delete(id: String) = engine.delete(id)
    fun deleteAll() = engine.deleteAll()
    fun stopStreaming() = engine.stopStreaming()
    fun dismissError() = engine.dismissError()
    fun submitAnswers(answers: List<AskAnswer>) = engine.submitAnswers(answers)
    fun declineQuestions() = engine.declineQuestions()
    fun activeChatHasImages(): Boolean = engine.activeChatHasImages()

    fun send(
        text: String,
        images: List<String>,
        files: List<to.eyed.spettro.chat.data.store.StoredFile>,
        model: ModelInfo?,
        thinking: ThinkingLevel,
    ) = engine.send(text, images, files, model, thinking)

    fun compact(model: ModelInfo?) = engine.compact(model)

    fun regenerate(model: ModelInfo?, thinking: ThinkingLevel) = engine.regenerate(model, thinking)

    /** One-shot outcome of an export/import, surfaced as a toast by the UI. */
    private val _dataNotice = MutableStateFlow<String?>(null)
    val dataNotice: StateFlow<String?> = _dataNotice.asStateFlow()

    fun clearDataNotice() {
        _dataNotice.value = null
    }

    fun exportChats(uri: Uri) {
        viewModelScope.launch {
            _dataNotice.value = try {
                val counts = container.backup.exportTo(uri)
                "Exported ${counts.chats} ${if (counts.chats == 1) "chat" else "chats"}, " +
                    "${counts.skills} skills, ${counts.memories} memories, " +
                    "${counts.mcpServers} MCP servers, and your settings."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Export failed: ${e.message?.take(120) ?: "could not write the file"}"
            }
        }
    }

    fun importChats(uri: Uri) {
        viewModelScope.launch {
            _dataNotice.value = try {
                val counts = container.backup.importFrom(uri)
                engine.refreshConversations()
                val parts = buildList {
                    if (counts.chats > 0) {
                        add(
                            "${counts.chats} ${if (counts.chats == 1) "chat" else "chats"}" +
                                (if (counts.chatsSkipped > 0) " (${counts.chatsSkipped} already up to date)" else ""),
                        )
                    }
                    if (counts.skills > 0) add("${counts.skills} skills")
                    if (counts.memories > 0) add("${counts.memories} memories")
                    if (counts.mcpServers > 0) add("${counts.mcpServers} MCP servers")
                    if (counts.settingsApplied) add("settings")
                }
                if (parts.isEmpty()) "Nothing new to import — everything in the file is already here."
                else "Imported ${parts.joinToString(", ")}."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: kotlinx.serialization.SerializationException) {
                "Import failed: this file is not a Spettro backup."
            } catch (e: Exception) {
                "Import failed: ${e.message?.take(120) ?: "could not read the file"}"
            }
        }
    }

    // Memory: remembered facts, editable by the user in Settings.
    val memories get() = container.memory.all
    fun addMemory(text: String) {
        viewModelScope.launch { container.memory.save(text) }
    }
    fun updateMemory(id: String, text: String) {
        viewModelScope.launch { container.memory.update(id, text) }
    }
    fun deleteMemory(id: String) {
        viewModelScope.launch { container.memory.delete(id) }
    }
    fun clearMemories() {
        viewModelScope.launch { container.memory.deleteAll() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container) as T
    }
}
