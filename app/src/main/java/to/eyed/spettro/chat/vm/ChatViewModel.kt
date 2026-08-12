package to.eyed.spettro.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.api.ChatEvent
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.api.OutgoingMessage
import to.eyed.spettro.chat.data.api.UnauthorizedException
import to.eyed.spettro.chat.data.store.Conversation
import to.eyed.spettro.chat.data.store.StoredMessage

sealed interface StreamState {
    data object Idle : StreamState
    /** Request sent, nothing received yet (or only reasoning so far). */
    data class Thinking(val reasoning: String = "") : StreamState
    data class Streaming(val text: String, val reasoning: String) : StreamState
    data class RateLimited(val retryAfterSeconds: Int) : StreamState
    data class Error(val message: String) : StreamState
}

class ChatViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val store = container.conversations

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _stream = MutableStateFlow<StreamState>(StreamState.Idle)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()

    private var sendJob: Job? = null

    val activeConversation: Conversation?
        get() = _conversations.value.firstOrNull { it.id == _activeId.value }

    init {
        viewModelScope.launch { _conversations.value = store.loadAll() }
    }

    fun newChat() {
        stopStreaming()
        _activeId.value = null
    }

    fun selectChat(id: String) {
        stopStreaming()
        _activeId.value = id
    }

    private fun upsert(conversation: Conversation) {
        _conversations.value =
            listOf(conversation) + _conversations.value.filter { it.id != conversation.id }
        viewModelScope.launch { store.save(conversation) }
    }

    private fun update(id: String, transform: (Conversation) -> Conversation) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        val updated = transform(conv)
        _conversations.value = _conversations.value.map { if (it.id == id) updated else it }
        viewModelScope.launch { store.save(updated) }
    }

    fun togglePin(id: String) = update(id) { it.copy(pinned = !it.pinned) }

    fun archive(id: String) {
        update(id) { it.copy(archived = true, pinned = false) }
        if (_activeId.value == id) _activeId.value = null
    }

    fun restore(id: String) = update(id) { it.copy(archived = false) }

    fun delete(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        if (_activeId.value == id) _activeId.value = null
        viewModelScope.launch { store.delete(id) }
    }

    fun deleteAll() {
        stopStreaming()
        _conversations.value = emptyList()
        _activeId.value = null
        viewModelScope.launch { store.deleteAll() }
    }

    fun stopStreaming() {
        sendJob?.cancel()
        sendJob = null
        // Keep whatever partial answer arrived.
        val s = _stream.value
        if (s is StreamState.Streaming && s.text.isNotBlank()) {
            appendAssistant(s.text, s.reasoning)
        }
        _stream.value = StreamState.Idle
    }

    fun dismissError() {
        if (_stream.value is StreamState.Error) _stream.value = StreamState.Idle
    }

    fun send(text: String, model: ModelInfo?, thinking: ThinkingLevel) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || sendJob?.isActive == true) return
        if (model == null) {
            _stream.value = StreamState.Error("Your plan has no models enabled yet.")
            return
        }

        val now = System.currentTimeMillis()
        val conv = activeConversation?.let {
            it.copy(
                messages = it.messages + StoredMessage("user", trimmed, at = now),
                updatedAt = now,
            )
        } ?: Conversation(
            id = store.newId(),
            title = trimmed.take(64),
            preview = trimmed.take(120),
            createdAt = now,
            updatedAt = now,
            messages = listOf(StoredMessage("user", trimmed, at = now)),
        )
        _activeId.value = conv.id
        upsert(conv)

        val history = conv.messages.map { OutgoingMessage(role = it.role, text = it.content) }
        // Only reasoning-capable models accept reasoning_effort.
        val effort = if (model.reasoning) thinking.effort else null

        _stream.value = StreamState.Thinking()
        sendJob = viewModelScope.launch {
            var textAcc = StringBuilder()
            var reasoningAcc = StringBuilder()
            try {
                api.chatStream(model.id, history, effort).collect { event ->
                    when (event) {
                        is ChatEvent.Reasoning -> {
                            reasoningAcc.append(event.delta)
                            if (textAcc.isEmpty()) {
                                _stream.value = StreamState.Thinking(reasoningAcc.toString())
                            }
                        }
                        is ChatEvent.Text -> {
                            textAcc.append(event.delta)
                            _stream.value = StreamState.Streaming(textAcc.toString(), reasoningAcc.toString())
                        }
                        is ChatEvent.RateLimited -> {
                            _stream.value = StreamState.RateLimited(event.retryAfterSeconds)
                        }
                        is ChatEvent.Usage -> Unit
                        is ChatEvent.Done -> {
                            appendAssistant(textAcc.toString(), reasoningAcc.toString())
                            _stream.value = StreamState.Idle
                        }
                    }
                }
            } catch (e: UnauthorizedException) {
                _stream.value = StreamState.Idle
                container.unauthorized.tryEmit(Unit)
            } catch (e: Exception) {
                if (textAcc.isNotBlank()) appendAssistant(textAcc.toString(), reasoningAcc.toString())
                _stream.value = StreamState.Error(friendlyError(e))
            }
        }
    }

    private fun appendAssistant(text: String, reasoning: String) {
        val id = _activeId.value ?: return
        if (text.isBlank() && reasoning.isBlank()) return
        update(id) {
            it.copy(
                messages = it.messages +
                    StoredMessage("assistant", text, thinking = reasoning, at = System.currentTimeMillis()),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.UnknownHostException || e is java.net.ConnectException ->
            "Can't reach Spettro. Check your connection and try again."
        else -> e.message?.take(200) ?: "Something went wrong. Please try again."
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container) as T
    }
}
