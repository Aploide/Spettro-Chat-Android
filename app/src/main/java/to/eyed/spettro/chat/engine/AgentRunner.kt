package to.eyed.spettro.chat.engine

import to.eyed.spettro.chat.data.AppPrefs
import to.eyed.spettro.chat.data.api.ChatEvent
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.api.OutgoingMessage
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.api.ToolCallData
import to.eyed.spettro.chat.data.api.UnauthorizedException
import to.eyed.spettro.chat.data.mcp.McpRegistry
import to.eyed.spettro.chat.data.memory.MemoryStore
import to.eyed.spettro.chat.data.store.StoredToolRun
import to.eyed.spettro.chat.data.tools.ToolRegistry
import to.eyed.spettro.chat.data.tools.ToolResult
import to.eyed.spettro.chat.vm.ThinkingLevel

/** Outcome of one headless run: the final answer plus the tools it used. */
data class HeadlessResult(
    val text: String,
    val toolRuns: List<StoredToolRun>,
    val failed: Boolean,
    val error: String? = null,
)

/**
 * The agent loop with nobody watching: same rounds, tools, and bounds as
 * [ChatEngine], but no stream state, no ask-user form, and no consent card.
 * Runs like this power spawned background tasks and scheduled tasks, both of
 * which may execute with the app off screen — so anything that would need to
 * stop and wait for the user degrades into an explicit tool error instead:
 *
 *  - `ask-user` is never offered; a stray call errors out.
 *  - Sensitive tools run only under a standing "Always allow" grant (plus the
 *    Android permission, already granted or not). There is no card to show.
 *  - `spawn-task` is not offered either: a background task must not fan out
 *    into more background tasks.
 */
class AgentRunner(
    private val api: SpettroApi,
    private val tools: ToolRegistry,
    private val mcp: McpRegistry,
    private val memory: MemoryStore,
    private val consent: ConsentGate,
    private val permissions: PermissionBridge,
    private val prefs: AppPrefs,
) {
    private companion object {
        const val MAX_TOOL_ROUNDS = 6

        val HEADLESS_PROMPT = """
            You are Spettro, a helpful assistant running on an Android phone.
            You are running as an unattended background task: the user is not watching and cannot
            answer questions, so never ask anything — make reasonable assumptions and finish the task.
            Call tools without asking permission and answer from the results.
            Use web-search and web-fetch for current events or live data, and current-time whenever
            the date or time matters — never guess it.
            Tools that touch personal data only work here if the user has already granted them
            standing approval; if such a tool returns a permission error, continue without that data
            and say what was unavailable.
            Your final message is delivered to the user as the task's result — make it a complete,
            self-contained answer.
        """.trimIndent()
    }

    /**
     * Runs [prompt] to completion. [onProgress] receives short human-readable
     * status lines (the running tool's label) for the task list UI.
     */
    suspend fun run(prompt: String, onProgress: (String) -> Unit = {}): HeadlessResult {
        // A cold process (WorkManager waking the app for a scheduled task)
        // has not decrypted the API key yet.
        if (prefs.apiKey == null) prefs.load()
        val model = resolveModel()
            ?: return HeadlessResult("", emptyList(), failed = true, error = "no model available — open the app and sign in")
        val effort = if (model.reasoning) ThinkingLevel.fromId(prefs.load().thinkingLevel).effort else null

        val memorySection = runCatching { memory.contextSection() }.getOrDefault("")
        val history = mutableListOf(
            OutgoingMessage(role = "system", text = HEADLESS_PROMPT + memorySection),
            OutgoingMessage(role = "user", text = prompt),
        )

        val textAcc = StringBuilder()
        val toolRuns = mutableListOf<StoredToolRun>()
        try {
            var round = 0
            while (true) {
                val offer = if (round < MAX_TOOL_ROUNDS) {
                    tools.specs.filter { it.name !in ToolRegistry.INTERACTIVE_ONLY } + mcp.activeSpecs()
                } else {
                    emptyList()
                }
                val roundText = StringBuilder()
                val calls = mutableListOf<ToolCallData>()
                api.chatStream(model.id, history, effort, offer).collect { event ->
                    when (event) {
                        is ChatEvent.Text -> {
                            roundText.append(event.delta)
                            textAcc.append(event.delta)
                        }
                        is ChatEvent.ToolCall -> calls += event.call
                        else -> Unit
                    }
                }
                if (calls.isEmpty()) break
                round++
                if (roundText.isNotEmpty()) textAcc.append("\n\n")
                history += OutgoingMessage(role = "assistant", text = roundText.toString(), toolCalls = calls)
                for (call in calls) {
                    onProgress(runningLabel(call))
                    val (result, label) = execute(call)
                    toolRuns += StoredToolRun(call.name, label, ok = !result.isError, output = result.output.take(20_000))
                    history += OutgoingMessage(role = "tool", text = result.output, toolCallId = call.id)
                }
            }
            val text = textAcc.toString().trim()
            return if (text.isBlank()) {
                HeadlessResult("", toolRuns, failed = true, error = "the model returned no answer")
            } else {
                HeadlessResult(text, toolRuns, failed = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            return HeadlessResult(textAcc.toString(), toolRuns, failed = true, error = "your Spettro session expired — open the app and sign in")
        } catch (e: Exception) {
            return HeadlessResult(textAcc.toString(), toolRuns, failed = true, error = e.message?.take(200) ?: "something went wrong")
        }
    }

    /** The user's selected model if the plan still has it, else the plan default. */
    private suspend fun resolveModel(): ModelInfo? {
        val snapshot = prefs.load()
        return snapshot.models.firstOrNull { it.id == snapshot.selectedModel }
            ?: snapshot.models.firstOrNull()
    }

    private fun runningLabel(call: ToolCallData): String = when {
        mcp.isMcpTool(call.name) -> mcp.runningLabel(call.name)
        else -> tools.runningLabel(call.name, call.arguments)
    }

    private suspend fun execute(call: ToolCallData): Pair<ToolResult, String> {
        if (call.name in ToolRegistry.INTERACTIVE_ONLY) {
            return ToolResult(
                "error: ${call.name} is not available in background tasks. Do not retry.",
                isError = true,
            ) to "Not available in background tasks"
        }
        if (mcp.isMcpTool(call.name)) {
            val info = mcp.consentInfoFor(call.name)
            if (info != null && !consent.hasStandingGrant(info.first)) {
                return ToolResult(
                    "error: ${call.name}: this MCP server has no standing approval, and a background " +
                        "task cannot ask for one. Do not retry; continue without it.",
                    isError = true,
                ) to "Needs approval in the app first"
            }
            return mcp.call(call.name, call.arguments) to mcp.doneLabel(call.name)
        }
        val meta = tools.sensitiveMeta(call.name, call.arguments)
        if (meta != null) {
            if (!consent.hasStandingGrant(meta.consentKey)) {
                return ToolResult(
                    "error: ${call.name}: the user has not granted standing approval for this data, and a " +
                        "background task cannot ask for it. Do not retry; continue without it.",
                    isError = true,
                ) to "Needs approval in the app first"
            }
            if (meta.permissions.any { !permissions.granted(it) }) {
                return ToolResult(
                    "error: ${call.name}: the Android permission is missing, so this data is unavailable. " +
                        "Do not retry; continue without it.",
                    isError = true,
                ) to "Android permission missing"
            }
        }
        return tools.execute(call) to tools.doneLabel(call.name, call.arguments)
    }
}
