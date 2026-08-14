package to.eyed.spettro.chat.engine

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.api.ChatEvent
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.api.OutgoingMessage
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.api.ToolCallData
import to.eyed.spettro.chat.data.api.UnauthorizedException
import to.eyed.spettro.chat.data.store.Conversation
import to.eyed.spettro.chat.data.store.ConversationStore
import to.eyed.spettro.chat.data.store.StoredMessage
import to.eyed.spettro.chat.data.store.StoredToolRun
import to.eyed.spettro.chat.data.tools.AskAnswer
import to.eyed.spettro.chat.data.tools.AskForm
import to.eyed.spettro.chat.data.tools.AskParseResult
import to.eyed.spettro.chat.data.tools.AskUserForms
import to.eyed.spettro.chat.data.skills.SkillsRepository.Companion.CREATE_SKILL
import to.eyed.spettro.chat.data.skills.SkillsRepository.Companion.LOAD_SKILL
import to.eyed.spettro.chat.data.tools.ToolRegistry
import to.eyed.spettro.chat.data.tools.ToolResult
import to.eyed.spettro.chat.vm.ContextEstimator
import to.eyed.spettro.chat.vm.StreamState
import to.eyed.spettro.chat.vm.ThinkingLevel
import to.eyed.spettro.chat.vm.ToolRunUi

/** One-shot happenings the foreground service turns into notifications. */
sealed interface EngineEvent {
    /**
     * A turn finished (or failed). [chatTitle] names what was being worked
     * on; the notification deliberately carries no message content — neither
     * the answer nor reasoning belongs on the lock screen.
     */
    data class RunFinished(val conversationId: String?, val chatTitle: String, val failed: Boolean) : EngineEvent

    /** The run is paused on something only the user can resolve. */
    data class NeedsInput(val reason: String) : EngineEvent
}

/**
 * The agentic chat loop, extracted from ChatViewModel so a turn survives the
 * Activity: it runs in an app-scoped coroutine scope, and [ChatRunService]
 * holds a foreground-service wakelock on the process while [isRunning].
 * ViewModels delegate here; the UI observes the same StateFlows as before.
 */
class ChatEngine(
    private val appContext: Context,
    private val api: SpettroApi,
    private val store: ConversationStore,
    private val tools: ToolRegistry,
    private val mcp: to.eyed.spettro.chat.data.mcp.McpRegistry,
    private val skills: to.eyed.spettro.chat.data.skills.SkillsRepository,
    private val memory: to.eyed.spettro.chat.data.memory.MemoryStore,
    private val consent: ConsentGate,
    private val permissions: PermissionBridge,
    private val prefs: to.eyed.spettro.chat.data.AppPrefs,
    private val unauthorized: MutableSharedFlow<Unit>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** True from send() until the turn settles back at Idle/Error; drives the service lifetime. */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    /**
     * Whether any activity is started; set by the ProcessLifecycleOwner
     * observer in SpettroChatApp. The service only notifies (completion,
     * needs-input) when the app is not on screen.
     */
    @Volatile
    var appVisible: Boolean = false

    /**
     * Skill picked for a chat that doesn't exist yet (no message sent);
     * applied to the conversation created by the next send().
     */
    private val _pendingSkillId = MutableStateFlow<String?>(null)
    val pendingSkillId: StateFlow<String?> = _pendingSkillId.asStateFlow()

    private var sendJob: Job? = null

    val activeConversation: Conversation?
        get() = _tempChat.value?.takeIf { it.id == _activeId.value }
            ?: _conversations.value.firstOrNull { it.id == _activeId.value }

    /**
     * The chat's title as of right now — not the copy captured when the turn
     * started, which predates the generated title.
     */
    private fun currentTitle(id: String): String =
        (_tempChat.value?.takeIf { it.id == id } ?: _conversations.value.firstOrNull { it.id == id })
            ?.displayTitle ?: "New Chat"

    init {
        scope.launch { _conversations.value = store.loadAll() }
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
            You also have tools for the user's calendar, contacts, reminders, location, and notifications,
            and tools that act on this phone: compose-message pre-fills an SMS or WhatsApp message (the
            user sends it themselves), set-alarm pre-fills an alarm or timer in the clock app, open-on-phone
            launches apps and links, and media-control works like a headset play/pause button. They touch
            personal data or act on the device, so the app itself shows the user an approval card before
            each first use — call them directly and never pre-ask in prose; if the user denies, accept it
            and continue without.
            You can work in the background: spawn-task starts an independent agent run for self-contained
            work (long research, drafting) whose result arrives as a new chat, and scheduled-tasks runs a
            prompt later or on a repeat ("every morning at 8, brief me on the weather"), delivered as a
            notification. Prefer answering directly; reach for these when the user asks for something to
            happen later, repeatedly, or alongside the conversation.
            You have a persistent memory across chats. Use save-memory when you learn a durable fact or
            preference about the user (name, language, tastes, ongoing projects) — one short line per fact.
            Use forget-memory when the user corrects or retracts something, or asks you to forget it.
            A Memory section appears below when anything is remembered; honor it without re-asking.
            You can also recall the past: search-history searches the user's earlier conversations and
            saved memories semantically, on this device. Use it when the user references something from
            before ("that place we talked about", "my project", "what did I tell you about…"), or before
            claiming you don't know something they may have told you in another chat.
            You can compute: run-javascript executes JavaScript in a secure sandbox (no network, no device
            access, 10s limit). Use it whenever precision matters — math, dates, statistics, parsing or
            transforming data — instead of calculating in your head; console.log output and the final
            value come back to you.
            You can produce artifacts the user keeps: create-file saves any text-based file (CSV, JSON,
            code, HTML…), generate-pdf lays out a real PDF document, and render-html displays an
            interactive HTML view (charts drawn with inline JS/canvas/SVG, styled tables, small widgets)
            inline in the chat — it must be fully self-contained, since network access is blocked there.
            Reach for these when the user asks for a file or chart, or when a document or visual would
            serve better than prose; never paste the generated content into your answer as well.
            The user can attach documents; each arrives inline in the message as an
            <attached-file name="..."> block holding the file's extracted text — treat it as the
            user's file and answer from its contents.
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
        _pendingSkillId.value = null
    }

    fun selectChat(id: String) {
        stopStreaming()
        discardTemp()
        _activeId.value = id
        _pendingSkillId.value = null
    }

    /** Applies (or clears) a skill on the active chat — or the next one. */
    fun setConversationSkill(skillId: String?) {
        val conv = activeConversation
        if (conv == null) {
            _pendingSkillId.value = skillId
        } else {
            update(conv.id) { it.copy(skillId = skillId) }
        }
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
        scope.launch { store.save(conversation) }
    }

    private fun update(id: String, transform: (Conversation) -> Conversation) {
        if (id == TEMP_ID) {
            _tempChat.value = _tempChat.value?.let(transform)
            return
        }
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        val updated = transform(conv)
        _conversations.value = _conversations.value.map { if (it.id == id) updated else it }
        scope.launch { store.save(updated) }
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
        scope.launch { store.delete(id) }
    }

    fun deleteAll() {
        stopStreaming()
        discardTemp()
        _conversations.value = emptyList()
        _activeId.value = null
        scope.launch { store.deleteAll() }
    }

    /** Re-reads the store, e.g. after the settings sheet imported chats. */
    suspend fun refreshConversations() {
        _conversations.value = store.loadAll()
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
        _isRunning.value = false
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

    fun send(
        text: String,
        images: List<String>,
        files: List<to.eyed.spettro.chat.data.store.StoredFile>,
        model: ModelInfo?,
        thinking: ThinkingLevel,
    ) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && images.isEmpty() && files.isEmpty()) || sendJob?.isActive == true) return
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
        val userMsg = StoredMessage("user", trimmed, at = now, images = images, files = files)
        val titleSeed = trimmed.ifBlank { files.firstOrNull()?.name ?: "Image" }
        val isFirstMessage = activeConversation?.messages?.isEmpty() != false
        val conv = activeConversation?.let {
            it.copy(messages = it.messages + userMsg, updatedAt = now)
        } ?: Conversation(
            id = store.newId(),
            title = titleSeed.take(64),
            preview = titleSeed.take(120),
            createdAt = now,
            updatedAt = now,
            skillId = _pendingSkillId.value,
            messages = listOf(userMsg),
        )
        _pendingSkillId.value = null
        _activeId.value = conv.id
        upsert(conv)
        startStream(conv, model, thinking)
        // Every chat gets a proper name as soon as it starts, so the sidebar
        // and the finished-run notification can say what it was about.
        // Temporary chats stay nameless on purpose.
        if (isFirstMessage && conv.id != TEMP_ID) generateTitle(conv.id, titleSeed, model)
    }

    /**
     * Asks the model for a short chat title in the background, alongside the
     * main turn. Best-effort: any failure just leaves the first-message seed.
     */
    private fun generateTitle(conversationId: String, firstMessage: String, model: ModelInfo) {
        scope.launch {
            runCatching {
                val request = listOf(
                    OutgoingMessage(
                        role = "system",
                        text = "You title chat conversations. Reply with only a title for the " +
                            "conversation that starts with the user message: at most 5 words, in the " +
                            "message's language, no quotes, no trailing punctuation.",
                    ),
                    OutgoingMessage(role = "user", text = firstMessage.take(2_000)),
                )
                val acc = StringBuilder()
                api.chatStream(model.id, request).collect { event ->
                    if (event is ChatEvent.Text) acc.append(event.delta)
                }
                val title = acc.toString()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .trim('"', '“', '”', '\'', '*', '#', ' ')
                    .take(64)
                if (title.isNotBlank()) {
                    update(conversationId) { it.copy(title = title) }
                }
            }
        }
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
        beginRun()
        sendJob = scope.launch {
            try {
                performCompaction(conv, model)
                _stream.value = StreamState.Idle
                _events.tryEmit(EngineEvent.RunFinished(conv.id, currentTitle(conv.id), failed = false))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                _stream.value = StreamState.Idle
                unauthorized.tryEmit(Unit)
            } catch (e: Exception) {
                _stream.value = StreamState.Error("Compacting failed: ${friendlyError(e)}")
                // The service's completion notification hangs off this event;
                // without it a backgrounded failure would end silently.
                _events.tryEmit(EngineEvent.RunFinished(conv.id, currentTitle(conv.id), failed = true))
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * Streams a self-contained summary of [conv] and replaces its history
     * with it. Throws on failure; callers own the stream state around it.
     */
    private suspend fun performCompaction(conv: Conversation, model: ModelInfo) {
        // Images are dropped from the request: they're the bulk of the
        // context, and non-vision models must be able to compact too. File
        // text stays — its facts belong in the summary.
        val history = conv.messages.map { OutgoingMessage(role = it.role, text = wireText(it)) } +
            OutgoingMessage(
                role = "user",
                text = "Summarize our conversation so far into a compact brief that preserves " +
                    "every fact, decision, constraint, code snippet, and open question needed " +
                    "to continue seamlessly. Reply with only the summary.",
            )
        val acc = StringBuilder()
        api.chatStream(model.id, history, null).collect { event ->
            when (event) {
                is ChatEvent.Text -> acc.append(event.delta)
                is ChatEvent.RateLimited -> _stream.value = StreamState.RateLimited(event.retryAfterSeconds)
                else -> Unit
            }
        }
        val summary = acc.toString().trim()
        check(summary.isNotBlank()) { "the model returned nothing" }
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
    }

    /**
     * Runs an automatic compaction at the end of a turn that left the chat
     * above [ContextEstimator.AUTO_COMPACT_RATIO]. Best-effort: a failure
     * leaves the history as-is and the 85% hard stop still protects the
     * next send.
     */
    private suspend fun maybeAutoCompact(conversationId: String, model: ModelInfo) {
        if (!prefs.autoCompact()) return
        val conv = _tempChat.value?.takeIf { it.id == conversationId }
            ?: _conversations.value.firstOrNull { it.id == conversationId }
            ?: return
        if (!ContextEstimator.shouldAutoCompact(conv.messages, model.contextWindow)) return
        _stream.value = StreamState.Compacting
        try {
            performCompaction(conv, model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * The message text as sent to the model: attached documents ride inline
     * ahead of the user's words, in the blocks the system prompt describes.
     */
    private fun wireText(msg: StoredMessage): String {
        if (msg.files.isEmpty()) return msg.content
        val blocks = msg.files.joinToString("\n\n") {
            "<attached-file name=\"${it.name.replace("\"", "'")}\">\n${it.text}\n</attached-file>"
        }
        return if (msg.content.isBlank()) blocks else "$blocks\n\n${msg.content}"
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
     * Marks a run active and raises the foreground service that keeps the
     * process (and its network) alive while the loop works. Only ever called
     * from a user interaction, so the app is in the foreground and the
     * startForegroundService call is always permitted.
     */
    private fun beginRun() {
        _isRunning.value = true
        runCatching { ChatRunService.start(appContext) }
    }

    /**
     * Runs one turn as an agentic loop, mirroring the CLI's runToolLoop:
     * stream a completion; if it ends in tool calls, execute them, append the
     * assistant tool-call turn plus one `role:"tool"` result per call, and
     * re-send. After [MAX_TOOL_ROUNDS] the request goes out without tools so
     * the model must answer with what it has gathered.
     */
    private fun startStream(conv: Conversation, model: ModelInfo, thinking: ThinkingLevel) {
        // Only reasoning-capable models accept reasoning_effort.
        val effort = if (model.reasoning) thinking.effort else null

        _stream.value = StreamState.Thinking()
        beginRun()
        sendJob = scope.launch {
            // The active skill's instructions and remembered facts ride along
            // in the system prompt; memory is re-read each turn, so facts
            // saved mid-turn appear from the next message on.
            val activeSkill = conv.skillId?.let { runCatching { skills.byId(it) }.getOrNull() }
            val memorySection = runCatching { memory.contextSection() }.getOrDefault("")
            val systemPrompt = SYSTEM_PROMPT + memorySection +
                (activeSkill?.let { "\n\n## Active skill: ${it.name}\n${it.instructions}" } ?: "")
            val history = mutableListOf(OutgoingMessage(role = "system", text = systemPrompt))
            conv.messages.mapTo(history) {
                OutgoingMessage(role = it.role, text = wireText(it), imageDataUrls = it.images)
            }
            // Rebuilt per run so the catalog in its description stays current.
            val loadSkillSpec = runCatching { skills.loadSkillSpec() }.getOrNull()
            val createSkillSpec = runCatching { skills.createSkillSpec() }.getOrNull()

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
                    // MCP specs come from a lazy, per-server-guarded listing —
                    // a dead server contributes nothing and never blocks.
                    val offer = if (round < MAX_TOOL_ROUNDS) {
                        tools.specs + listOfNotNull(loadSkillSpec, createSkillSpec) + mcp.activeSpecs()
                    } else {
                        emptyList()
                    }
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
                                toolRuns += ToolRunUi(event.name, runningLabel(event.name, ""), running = true)
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
                    // Parallel-safe calls (web, device reads) of the same
                    // round start together; interactive and gated calls stay
                    // sequential so consent cards and forms appear one at a
                    // time. Results are appended in call order regardless.
                    coroutineScope {
                        val prefetched = calls.map { call ->
                            if (calls.size > 1 && tools.isParallelSafe(call.name)) {
                                async {
                                    tools.execute(call) to tools.doneLabel(call.name, call.arguments)
                                }
                            } else {
                                null
                            }
                        }
                        calls.forEachIndexed { i, call ->
                            // The non-streamed fallback emits no ToolCallStart, so
                            // the placeholder row may not exist yet.
                            val idx = if (runBase + i < toolRuns.size) runBase + i else {
                                toolRuns += ToolRunUi(call.name, "", running = true)
                                toolRuns.size - 1
                            }
                            toolRuns[idx] = ToolRunUi(call.name, runningLabel(call.name, call.arguments), running = true)
                            publish()
                            val meta = tools.sensitiveMeta(call.name, call.arguments)
                            val (result, doneLabel) = prefetched[i]?.await() ?: when {
                                call.name == ToolRegistry.ASK_USER -> executeAskUser(call)
                                call.name == LOAD_SKILL -> executeLoadSkill(call)
                                call.name == CREATE_SKILL -> executeCreateSkill(call)
                                mcp.isMcpTool(call.name) -> executeMcp(call)
                                meta != null -> executeGated(meta, call)
                                else -> tools.execute(call) to tools.doneLabel(call.name, call.arguments)
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
                }
                appendAssistant(textAcc.toString(), reasoningAcc.toString(), toolRuns.toStored())
                // A turn that grew the chat past the auto-compact threshold is
                // folded into a summary now, before the user sends again.
                maybeAutoCompact(conv.id, model)
                _stream.value = StreamState.Idle
                _events.tryEmit(EngineEvent.RunFinished(conv.id, currentTitle(conv.id), failed = false))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A stopped turn is not an error; stopStreaming already kept
                // the partial answer.
                throw e
            } catch (e: UnauthorizedException) {
                _stream.value = StreamState.Idle
                unauthorized.tryEmit(Unit)
            } catch (e: Exception) {
                if (textAcc.isNotBlank()) {
                    appendAssistant(textAcc.toString(), reasoningAcc.toString(), toolRuns.toStored())
                }
                _stream.value = StreamState.Error(friendlyError(e))
                _events.tryEmit(EngineEvent.RunFinished(conv.id, currentTitle(conv.id), failed = true))
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * The mandatory approval path for tools touching personal data: first the
     * in-app consent card (Allow once / Always allow / Deny), then the
     * Android runtime permission if one is needed. Either refusal returns an
     * explicit tool error so the model explains instead of retrying.
     */
    private suspend fun executeGated(
        meta: to.eyed.spettro.chat.data.tools.SensitiveMeta,
        call: ToolCallData,
    ): Pair<ToolResult, String> {
        // The service watches consent.pending/permissions.pending and posts
        // the "needs your input" notification itself when backgrounded.
        val allowed = consent.require(ConsentRequest(meta.consentKey, meta.consentTitle, meta.consentDetail))
        if (!allowed) {
            return ToolResult(
                "error: ${call.name}: the user declined to share this data. Do not retry; " +
                    "answer without it.",
                isError = true,
            ) to "You declined — ${friendlyConsentName(meta.consentKey)} stays private"
        }
        if (meta.permissions.isNotEmpty() && !permissions.ensure(meta.permissions, meta.rationale)) {
            return ToolResult(
                "error: ${call.name}: the Android permission was refused, so this data is unavailable. " +
                    "Do not retry; answer without it.",
                isError = true,
            ) to "Android permission refused"
        }
        return tools.execute(call) to tools.doneLabel(call.name, call.arguments)
    }

    private fun runningLabel(name: String, argumentsJson: String): String = when {
        name == LOAD_SKILL -> "Loading a skill…"
        name == CREATE_SKILL -> "Creating a skill…"
        mcp.isMcpTool(name) -> mcp.runningLabel(name)
        else -> tools.runningLabel(name, argumentsJson)
    }

    private suspend fun executeLoadSkill(call: ToolCallData): Pair<ToolResult, String> {
        val result = skills.executeLoad(call.arguments)
        val slug = to.eyed.spettro.chat.data.tools.ToolArgs.string(call.arguments, "name")
        return result to if (result.isError) "Skill not found" else "Loaded the ${slug ?: ""} skill".trim()
    }

    private suspend fun executeCreateSkill(call: ToolCallData): Pair<ToolResult, String> {
        val result = skills.executeCreate(call.arguments)
        val name = to.eyed.spettro.chat.data.tools.ToolArgs.string(call.arguments, "name")
        return result to when {
            result.isError -> "Couldn't create the skill"
            name != null -> "Created the “$name” skill"
            else -> "Created a skill"
        }
    }

    /**
     * MCP calls go through the same mandatory consent card, granted per
     * server (one grant covers all of that server's tools).
     */
    private suspend fun executeMcp(call: ToolCallData): Pair<ToolResult, String> {
        val info = mcp.consentInfoFor(call.name)
        if (info != null) {
            val (key, title, detail) = info
            if (!consent.require(ConsentRequest(key, title, detail))) {
                return ToolResult(
                    "error: ${call.name}: the user declined to let this MCP server run. " +
                        "Do not retry; answer without it.",
                    isError = true,
                ) to "You declined the MCP call"
            }
        }
        return mcp.call(call.name, call.arguments) to mcp.doneLabel(call.name)
    }

    private fun friendlyConsentName(consentKey: String): String = when (consentKey) {
        "tool:${ToolRegistry.CALENDAR_EVENTS}" -> "your calendar"
        "tool:${ToolRegistry.CONTACTS_SEARCH}" -> "your contacts"
        "tool:${ToolRegistry.SET_REMINDER}" -> "reminders"
        "tool:${ToolRegistry.GET_LOCATION}" -> "your location"
        "tool:${ToolRegistry.SCHEDULED_TASKS}" -> "scheduling"
        "tool:${ToolRegistry.COMPOSE_MESSAGE}" -> "messaging"
        "tool:${ToolRegistry.SET_ALARM}" -> "your alarms"
        "tool:${ToolRegistry.OPEN_ON_PHONE}" -> "opening apps"
        "tool:${ToolRegistry.MEDIA_CONTROL}" -> "media control"
        "tool:${ToolRegistry.READ_NOTIFICATIONS}" -> "your notifications"
        else -> consentKey.removePrefix("tool:").removePrefix("mcp:")
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
        _events.tryEmit(EngineEvent.NeedsInput("Spettro has a question for you"))
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
}
