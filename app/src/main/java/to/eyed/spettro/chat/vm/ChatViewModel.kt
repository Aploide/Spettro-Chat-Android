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
    /** The conversation is being summarized to free up context. */
    data object Compacting : StreamState
    data class Error(val message: String) : StreamState
}

/**
 * Rough context accounting so the app can stop a chat before it overflows
 * the model's window: ~4 chars per token for text, a flat estimate per
 * attached image.
 */
object ContextEstimator {
    private const val CHARS_PER_TOKEN = 4
    private const val TOKENS_PER_IMAGE = 1600
    private const val RESERVED_FOR_REPLY = 0.15f

    /** Block sending once the history uses this share of the window. */
    const val BLOCK_RATIO = 0.85f

    fun estimateTokens(messages: List<StoredMessage>): Int = messages.sumOf {
        (it.content.length + it.thinking.length) / CHARS_PER_TOKEN + it.images.size * TOKENS_PER_IMAGE
    }

    /** Used share of the model's window; 0 when the window is unknown. */
    fun usedRatio(messages: List<StoredMessage>, contextWindow: Int): Float {
        if (contextWindow <= 0) return 0f
        return estimateTokens(messages).toFloat() / contextWindow
    }

    fun isNearLimit(messages: List<StoredMessage>, contextWindow: Int): Boolean =
        usedRatio(messages, contextWindow) >= BLOCK_RATIO
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

    /** True when any message in the active conversation carries images. */
    fun activeChatHasImages(): Boolean =
        activeConversation?.messages?.any { it.images.isNotEmpty() } == true

    fun send(text: String, images: List<String>, model: ModelInfo?, thinking: ThinkingLevel) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && images.isEmpty()) || sendJob?.isActive == true) return
        if (model == null) {
            _stream.value = StreamState.Error("Your plan has no models enabled yet.")
            return
        }
        // A chat with images must stay on a vision-capable model.
        val hasImages = images.isNotEmpty() || activeChatHasImages()
        if (hasImages && !model.vision) {
            _stream.value = StreamState.Error(
                "This chat contains images. Switch to a vision-capable model to continue.",
            )
            return
        }
        // Refuse to run into the context ceiling; the UI offers compact/new chat.
        val history = activeConversation?.messages ?: emptyList()
        if (ContextEstimator.isNearLimit(history, model.contextWindow)) {
            _stream.value = StreamState.Error(
                "This chat is near the model's context limit. Compact it or start a new chat.",
            )
            return
        }

        val now = System.currentTimeMillis()
        val userMsg = StoredMessage("user", trimmed, at = now, images = images)
        val titleSeed = trimmed.ifBlank { "Image" }
        val conv = activeConversation?.let {
            it.copy(messages = it.messages + userMsg, updatedAt = now)
        } ?: Conversation(
            id = store.newId(),
            title = titleSeed.take(64),
            preview = titleSeed.take(120),
            createdAt = now,
            updatedAt = now,
            messages = listOf(userMsg),
        )
        _activeId.value = conv.id
        upsert(conv)
        startStream(conv, model, thinking)
    }

    /**
     * Compacts the active conversation: asks the model for a self-contained
     * summary, then replaces the history with it. Frees context and drops
     * image payloads.
     */
    fun compact(model: ModelInfo?) {
        val conv = activeConversation ?: return
        if (model == null || conv.messages.isEmpty() || sendJob?.isActive == true) return
        _stream.value = StreamState.Compacting
        sendJob = viewModelScope.launch {
            // Images are dropped from the request: they're the bulk of the
            // context, and non-vision models must be able to compact too.
            val history = conv.messages.map { OutgoingMessage(role = it.role, text = it.content) } +
                OutgoingMessage(
                    role = "user",
                    text = "Summarize our conversation so far into a compact brief that preserves " +
                        "every fact, decision, constraint, code snippet, and open question needed " +
                        "to continue seamlessly. Reply with only the summary.",
                )
            val acc = StringBuilder()
            try {
                api.chatStream(model.id, history, null).collect { event ->
                    when (event) {
                        is ChatEvent.Text -> acc.append(event.delta)
                        is ChatEvent.RateLimited -> _stream.value = StreamState.RateLimited(event.retryAfterSeconds)
                        is ChatEvent.Done -> {
                            val summary = acc.toString().trim()
                            if (summary.isBlank()) {
                                _stream.value = StreamState.Error("Compacting failed — the model returned nothing.")
                            } else {
                                update(conv.id) {
                                    it.copy(
                                        messages = listOf(
                                            StoredMessage(
                                                role = "assistant",
                                                content = "**Conversation compacted.** Summary of the discussion so far:\n\n$summary",
                                                at = System.currentTimeMillis(),
                                            ),
                                        ),
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                }
                                _stream.value = StreamState.Idle
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (e: UnauthorizedException) {
                _stream.value = StreamState.Idle
                container.unauthorized.tryEmit(Unit)
            } catch (e: Exception) {
                _stream.value = StreamState.Error("Compacting failed: ${friendlyError(e)}")
            }
        }
    }

    /** Drops the trailing assistant reply and re-runs the last user turn. */
    fun regenerate(model: ModelInfo?, thinking: ThinkingLevel) {
        if (model == null || sendJob?.isActive == true) return
        val conv = activeConversation ?: return
        val trimmedMsgs = conv.messages.dropLastWhile { it.role == "assistant" }
        if (trimmedMsgs.isEmpty()) return
        val updated = conv.copy(messages = trimmedMsgs, updatedAt = System.currentTimeMillis())
        upsert(updated)
        startStream(updated, model, thinking)
    }

    private fun startStream(conv: Conversation, model: ModelInfo, thinking: ThinkingLevel) {
        val history = conv.messages.map {
            OutgoingMessage(role = it.role, text = it.content, imageDataUrls = it.images)
        }
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
