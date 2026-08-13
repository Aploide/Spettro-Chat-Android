package to.eyed.spettro.chat.data.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.spettro.chat.data.api.ToolCallData
import to.eyed.spettro.chat.data.api.ToolSpec
import java.net.URL

data class ToolResult(val output: String, val isError: Boolean = false)

/**
 * The tools offered to the model on every chat request, mirroring the CLI's
 * native tool calling (internal/agent/llm_runtime_prompt.go) trimmed to what
 * makes sense on a phone. This file owns the specs, labels, and dispatch;
 * implementations live in [WebTools], [DeviceTools], and AskUser.kt.
 */
class ToolRegistry(context: Context) {
    private val web = WebTools()
    private val device = DeviceTools(context)

    companion object {
        const val WEB_SEARCH = "web-search"
        const val WEB_FETCH = "web-fetch"
        const val CURRENT_TIME = "current-time"
        const val DEVICE_INFO = "device-info"
        const val ASK_USER = "ask-user"
        const val COMMENT = "comment"
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = WEB_SEARCH,
            description = "Search the web. Returns result titles, URLs, and snippets. " +
                "Use for current events, facts you are unsure of, or anything after your training data.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"The search query."},"max_results":{"type":"integer","description":"Maximum results to return (default 10)."}},"required":["query"]}""",
        ),
        ToolSpec(
            name = WEB_FETCH,
            description = "Fetch a URL and return its readable text content. Use after web-search to read a promising result.",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"The http(s) URL to fetch."},"max_length":{"type":"integer","description":"Maximum characters of text to return (default 20000)."}},"required":["url"]}""",
        ),
        ToolSpec(
            name = CURRENT_TIME,
            description = "Get the current date and time from the device clock. " +
                "Use for any question involving today's date, the time, weekdays, or elapsed time.",
            parametersJson = """{"type":"object","properties":{"timezone":{"type":"string","description":"Optional IANA timezone (e.g. Europe/Rome). Defaults to the device timezone."}},"required":[]}""",
        ),
        ToolSpec(
            name = DEVICE_INFO,
            description = "Read this Android device's status: battery level and charging state, network connectivity, locale, timezone, and device model.",
            parametersJson = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = COMMENT,
            description = "Emit a progress message visible to the user. " +
                "Use it to report meaningful steps during longer multi-tool runs.",
            parametersJson = """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""",
        ),
        // Description and schema verbatim from the CLI
        // (internal/agent/llm_runtime_prompt.go), so prompting behaves alike.
        ToolSpec(
            name = ASK_USER,
            description = "Ask the user up to 4 related questions as one form and wait for their answers. " +
                "Use it when a decision is genuinely the user's to make and proceeding on a guess would waste work — " +
                "never for something you can determine yourself. Batch questions that belong to the same decision " +
                "into one call rather than interrupting repeatedly. Each question takes a short header (the label " +
                "the UI shows), the question line, and up to 8 options; give every option a label plus a one-line " +
                "description of what choosing it means, mark the one you would pick with is_recommended (the UI " +
                "highlights it), and set preview when there is concrete content — a snippet, a layout, a config — " +
                "worth showing beside the option. Set multi_select when several answers can hold at once: there is " +
                "no exclusivity flag, so phrase those options such that any subset of them reads sensibly. Set " +
                "allow_custom when written input is useful: the user gets a free-text entry and their words come " +
                "back verbatim, quoted. Answers return one line per question, keyed by header; a question the user " +
                "skipped is marked as unanswered, so never read silence as agreement with your recommendation, and " +
                "a multi-select question answered with none of the options is marked as such — that is a decision " +
                "about them, not silence.",
            parametersJson = """{"type":"object","properties":{"questions":{"type":"array","maxItems":4,"description":"the form: up to 4 questions answered in one interaction","items":{"type":"object","properties":{"header":{"type":"string","description":"short label, e.g. \"Focus area\"; must be unique within the form and keys the answer"},"question":{"type":"string","description":"the full question line"},"options":{"type":"array","maxItems":8,"description":"selectable answers; prefer these over an open question","items":{"type":"object","properties":{"label":{"type":"string","description":"the answer as the user reads it"},"description":{"type":"string","description":"one muted line under the label saying what choosing it means"},"preview":{"type":"string","description":"preformatted content (snippet, layout, config) shown beside the option; kept verbatim, so keep lines narrow"},"is_recommended":{"type":"boolean","description":"the answer you would pick; highlighted"}},"required":["label"]}},"multi_select":{"type":"boolean","description":"several answers may be chosen at once; any subset can come back, so phrase the options so every combination of them means something"},"allow_custom":{"type":"boolean","description":"also offer a free-text entry; the typed answer is returned verbatim"}},"required":["question"]}},"context":{"type":"string","description":"one line of background applying to the whole form"},"question":{"type":"string","description":"legacy single-question form; use questions[] instead"},"options":{"type":"array","items":{"type":"string"},"description":"legacy: option labels for the single question"},"default_option":{"type":"string","description":"legacy: the recommended option, matched by label"},"allow_free_response":{"type":"boolean","description":"legacy: allow_custom for the single question"}}}""",
        ),
    )

    /** Label shown while a call runs, e.g. `Searching the web for "x"…`. */
    fun runningLabel(name: String, argumentsJson: String): String = when (name) {
        WEB_SEARCH -> quotedArg(argumentsJson, "query")
            ?.let { "Searching the web for $it…" } ?: "Searching the web…"
        WEB_FETCH -> hostArg(argumentsJson)?.let { "Reading $it…" } ?: "Reading a web page…"
        CURRENT_TIME -> "Checking the time…"
        DEVICE_INFO -> "Reading device status…"
        ASK_USER -> "Waiting for your answer…"
        COMMENT -> commentMessage(argumentsJson) ?: "…"
        else -> "Running $name…"
    }

    /** Label shown once a call finished, e.g. `Searched the web for "x"`. */
    fun doneLabel(name: String, argumentsJson: String): String = when (name) {
        WEB_SEARCH -> quotedArg(argumentsJson, "query")
            ?.let { "Searched the web for $it" } ?: "Searched the web"
        WEB_FETCH -> hostArg(argumentsJson)?.let { "Read $it" } ?: "Read a web page"
        CURRENT_TIME -> "Checked the time"
        DEVICE_INFO -> "Read device status"
        ASK_USER -> "Asked for your input"
        // A comment's whole point is its text; the label is the message.
        COMMENT -> commentMessage(argumentsJson) ?: "…"
        else -> "Ran $name"
    }

    suspend fun execute(call: ToolCallData): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (call.name) {
                WEB_SEARCH -> web.search(call.arguments)
                WEB_FETCH -> web.fetch(call.arguments)
                CURRENT_TIME -> device.currentTime(call.arguments)
                DEVICE_INFO -> device.deviceInfo()
                // The CLI echoes the message back verbatim as the result.
                COMMENT -> ToolResult(ToolArgs.string(call.arguments, "message") ?: "")
                // ask-user blocks on the person; the ViewModel intercepts it
                // before execution ever reaches the registry.
                ASK_USER -> ToolResult("error: ask-user: interactive callback not configured", isError = true)
                else -> ToolResult("Unknown tool: ${call.name}", isError = true)
            }
        } catch (e: Exception) {
            ToolResult("Tool ${call.name} failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun commentMessage(argumentsJson: String): String? =
        ToolArgs.string(argumentsJson, "message")?.take(300)

    private fun quotedArg(argumentsJson: String, key: String): String? =
        ToolArgs.string(argumentsJson, key)?.let { "“${it.take(60)}”" }

    private fun hostArg(argumentsJson: String): String? =
        ToolArgs.string(argumentsJson, "url")?.let { runCatching { URL(it).host }.getOrNull() }
}
