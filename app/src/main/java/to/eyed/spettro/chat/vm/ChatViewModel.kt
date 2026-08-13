package to.eyed.spettro.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.api.ChatEvent
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.api.OutgoingMessage
import to.eyed.spettro.chat.data.api.ToolCallData
import to.eyed.spettro.chat.data.api.UnauthorizedException
import to.eyed.spettro.chat.data.store.Conversation
import to.eyed.spettro.chat.data.store.StoredMessage
import to.eyed.spettro.chat.data.store.StoredToolRun
import to.eyed.spettro.chat.data.tools.AskAnswer
import to.eyed.spettro.chat.data.tools.AskForm
import to.eyed.spettro.chat.data.tools.AskParseResult
import to.eyed.spettro.chat.data.tools.AskUserForms
import to.eyed.spettro.chat.data.tools.ToolRegistry
import to.eyed.spettro.chat.data.tools.ToolResult

// StreamState, ToolRunUi, and ContextEstimator live in StreamState.kt.
class ChatViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val store = container.conversations

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _stream = MutableStateFlow<StreamState>(StreamState.Idle)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()

    /** Non-null while an ask-user form is on screen waiting for the user. */
    private val _askForm = MutableStateFlow<AskForm?>(null)
    val askForm: StateFlow<AskForm?> = _askForm.asStateFlow()
    private var askReply: CompletableDeferred<List<AskAnswer>?>? = null

    /**
     * A temporary chat lives only in memory: never written to the store,
     * never listed in the sidebar, gone when it's left or the app closes.
     */
    private val _tempChat = MutableStateFlow<Conversation?>(null)
    val tempChat: StateFlow<Conversation?> = _tempChat.asStateFlow()
    private val _isTemporary = MutableStateFlow(false)
    val isTemporary: StateFlow<Boolean> = _isTemporary.asStateFlow()

    private var sendJob: Job? = null

    val activeConversation: Conversation?
        get() = _tempChat.value?.takeIf { it.id == _activeId.value }
            ?: _conversations.value.firstOrNull { it.id == _activeId.value }

    init {
        viewModelScope.launch { _conversations.value = store.loadAll() }
    }

    private companion object {
        const val TEMP_ID = "temporary"

        /** After this many tool rounds the request goes out without tools, forcing an answer. */
        const val MAX_TOOL_ROUNDS = 6

        /** Minimum gap between stream-state publishes while tokens arrive. */
        const val PUBLISH_INTERVAL_MS = 80L

        val SYSTEM_PROMPT = """
            You are Spettro, a helpful assistant running on an Android phone.
            You can call tools; use them without asking permission, then answer from the results.
            Use web-search and web-fetch for current events, live data, or facts you are not sure about.
            Use current-time whenever today's date or the time matters — never guess it.
            Use device-info for questions about this phone's battery, network, or locale.
            When a decision is genuinely the user's to make and guessing would waste work, use ask-user
            to present the choice as a form instead of asking in prose.
            During longer multi-tool runs, use the comment tool to report meaningful progress steps.
            When no tool is needed, just answer directly.
        """.trimIndent()
    }

    fun newChat() {
        stopStreaming()
        discardTemp()
        _activeId.value = null
    }

    fun selectChat(id: String) {
        stopStreaming()
        discardTemp()
        _activeId.value = id
    }

    /** Enter (or leave) a throwaway conversation. */
    fun toggleTemporaryChat() {
        stopStreaming()
        if (_isTemporary.value) {
            discardTemp()
            _activeId.value = null
            return
        }
        val now = System.currentTimeMillis()
        _tempChat.value = Conversation(id = TEMP_ID, createdAt = now, updatedAt = now)
        _isTemporary.value = true
        _activeId.value = TEMP_ID
    }

    private fun discardTemp() {
        _tempChat.value = null
        _isTemporary.value = false
    }

    private fun upsert(conversation: Conversation) {
        if (conversation.id == TEMP_ID) {
            _tempChat.value = conversation
            return
        }
        _conversations.value =
            listOf(conversation) + _conversations.value.filter { it.id != conversation.id }
        viewModelScope.launch { store.save(conversation) }
    }

    private fun update(id: String, transform: (Conversation) -> Conversation) {
        if (id == TEMP_ID) {
            _tempChat.value = _tempChat.value?.let(transform)
            return
        }
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
        discardTemp()
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
            appendAssistant(s.text, s.reasoning, s.tools.toStored())
        }
        _stream.value = StreamState.Idle
    }

    fun dismissError() {
        if (_stream.value is StreamState.Error) _stream.value = StreamState.Idle
    }

    /** The user submitted the ask-user form; the tool loop resumes with the answers. */
    fun submitAnswers(answers: List<AskAnswer>) {
        _askForm.value = null
        askReply?.complete(answers)
    }

    /** The user declined the form; the model gets an explicit decline, not silence. */
    fun declineQuestions() {
        _askForm.value = null
        askReply?.complete(null)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

    /**
     * Runs one turn as an agentic loop, mirroring the CLI's runToolLoop:
     * stream a completion; if it ends in tool calls, execute them, append the
     * assistant tool-call turn plus one `role:"tool"` result per call, and
     * re-send. After [MAX_TOOL_ROUNDS] the request goes out without tools so
     * the model must answer with what it has gathered.
     */
    private fun startStream(conv: Conversation, model: ModelInfo, thinking: ThinkingLevel) {
        val history = mutableListOf(OutgoingMessage(role = "system", text = SYSTEM_PROMPT))
        conv.messages.mapTo(history) {
            OutgoingMessage(role = it.role, text = it.content, imageDataUrls = it.images)
        }
        // Only reasoning-capable models accept reasoning_effort.
        val effort = if (model.reasoning) thinking.effort else null
        val tools = container.tools

        _stream.value = StreamState.Thinking()
        sendJob = viewModelScope.launch {
            val textAcc = StringBuilder()
            val reasoningAcc = StringBuilder()
            val toolRuns = mutableListOf<ToolRunUi>()

            var lastPublish = 0L

            fun publish() {
                lastPublish = System.currentTimeMillis()
                _stream.value = if (textAcc.isEmpty()) {
                    StreamState.Thinking(reasoningAcc.toString(), toolRuns.toList())
                } else {
                    StreamState.Streaming(textAcc.toString(), reasoningAcc.toString(), toolRuns.toList())
                }
            }

            // Token deltas arrive faster than markdown can re-parse without
            // flicker; batching them keeps the stream visually stable.
            fun publishThrottled() {
                if (System.currentTimeMillis() - lastPublish >= PUBLISH_INTERVAL_MS) publish()
            }

            try {
                var round = 0
                while (true) {
                    val offer = if (round < MAX_TOOL_ROUNDS) tools.specs else emptyList()
                    val roundText = StringBuilder()
                    val calls = mutableListOf<ToolCallData>()
                    val runBase = toolRuns.size
                    api.chatStream(model.id, history, effort, offer).collect { event ->
                        when (event) {
                            is ChatEvent.Reasoning -> {
                                reasoningAcc.append(event.delta)
                                publishThrottled()
                            }
                            is ChatEvent.Text -> {
                                roundText.append(event.delta)
                                textAcc.append(event.delta)
                                publishThrottled()
                            }
                            is ChatEvent.ToolCallStart -> {
                                // Show activity as soon as the model commits
                                // to a tool; arguments are still streaming.
                                toolRuns += ToolRunUi(event.name, tools.runningLabel(event.name, ""), running = true)
                                publish()
                            }
                            is ChatEvent.ToolCall -> calls += event.call
                            is ChatEvent.RateLimited -> {
                                _stream.value = StreamState.RateLimited(event.retryAfterSeconds)
                            }
                            is ChatEvent.Usage -> Unit
                            is ChatEvent.Done -> Unit
                        }
                    }
                    // Flush whatever the throttle was still holding back.
                    publish()
                    if (calls.isEmpty()) break
                    round++
                    // Interim text ("Let me check…") stays visible; separate
                    // it from whatever the next round streams.
                    if (roundText.isNotEmpty()) textAcc.append("\n\n")
                    history += OutgoingMessage(role = "assistant", text = roundText.toString(), toolCalls = calls)
                    calls.forEachIndexed { i, call ->
                        // The non-streamed fallback emits no ToolCallStart, so
                        // the placeholder row may not exist yet.
                        val idx = if (runBase + i < toolRuns.size) runBase + i else {
                            toolRuns += ToolRunUi(call.name, "", running = true)
                            toolRuns.size - 1
                        }
                        toolRuns[idx] = ToolRunUi(call.name, tools.runningLabel(call.name, call.arguments), running = true)
                        publish()
                        val (result, doneLabel) = if (call.name == ToolRegistry.ASK_USER) {
                            executeAskUser(call)
                        } else {
                            tools.execute(call) to tools.doneLabel(call.name, call.arguments)
                        }
                        toolRuns[idx] = ToolRunUi(
                            call.name,
                            doneLabel,
                            running = false,
                            failed = result.isError,
                            output = result.output,
                        )
                        publish()
                        history += OutgoingMessage(role = "tool", text = result.output, toolCallId = call.id)
                    }
                }
                appendAssistant(textAcc.toString(), reasoningAcc.toString(), toolRuns.toStored())
                _stream.value = StreamState.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A stopped turn is not an error; stopStreaming already kept
                // the partial answer.
                throw e
            } catch (e: UnauthorizedException) {
                _stream.value = StreamState.Idle
                container.unauthorized.tryEmit(Unit)
            } catch (e: Exception) {
                if (textAcc.isNotBlank()) {
                    appendAssistant(textAcc.toString(), reasoningAcc.toString(), toolRuns.toStored())
                }
                _stream.value = StreamState.Error(friendlyError(e))
            }
        }
    }

    /**
     * Shows the ask-user form and suspends until the user submits or
     * declines. Mirrors the CLI: no timeout — the tool waits on a person —
     * and a decline is an explicit error result, never silence. Returns the
     * tool result plus the label persisted in the transcript (the answers
     * themselves, so history shows what was decided).
     */
    private suspend fun executeAskUser(call: ToolCallData): Pair<ToolResult, String> {
        val form = when (val parsed = AskUserForms.parse(call.arguments)) {
            is AskParseResult.Invalid ->
                return ToolResult("error: ask-user: ${parsed.message}", isError = true) to
                    "Question form was invalid"
            is AskParseResult.Ok -> parsed.form
        }
        val reply = CompletableDeferred<List<AskAnswer>?>()
        askReply = reply
        _askForm.value = form
        try {
            val answers = reply.await()
                ?: return ToolResult("error: ask-user: user declined to answer", isError = true) to
                    "You declined to answer"
            val formatted = AskUserForms.formatAnswers(form, answers)
                ?: return ToolResult("error: ask-user: user did not answer", isError = true) to
                    "You declined to answer"
            return ToolResult(formatted) to formatted
        } finally {
            _askForm.value = null
            askReply = null
        }
    }

    private fun List<ToolRunUi>.toStored(): List<StoredToolRun> =
        filter { !it.running }.map {
            // Cap stored outputs so a fetched page can't bloat the chat file.
            StoredToolRun(it.name, it.label, ok = !it.failed, output = it.output.take(20_000))
        }

    private fun appendAssistant(text: String, reasoning: String, tools: List<StoredToolRun> = emptyList()) {
        val id = _activeId.value ?: return
        if (text.isBlank() && reasoning.isBlank()) return
        update(id) {
            it.copy(
                messages = it.messages +
                    StoredMessage(
                        "assistant",
                        text,
                        thinking = reasoning,
                        at = System.currentTimeMillis(),
                        tools = tools,
                    ),
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
