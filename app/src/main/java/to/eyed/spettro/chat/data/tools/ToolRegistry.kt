package to.eyed.spettro.chat.data.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import to.eyed.spettro.chat.data.api.ToolCallData
import to.eyed.spettro.chat.data.api.ToolSpec
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ToolResult(val output: String, val isError: Boolean = false)

/**
 * The tools offered to the model on every chat request, mirroring the CLI's
 * native tool calling (internal/agent/llm_runtime_prompt.go) trimmed to what
 * makes sense on a phone: web search + fetch (same DuckDuckGo scrape as the
 * CLI's web-search), live clock, and device status.
 */
class ToolRegistry(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    // Same UA as the CLI's agent fetches, so DDG treats both alike.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val WEB_SEARCH = "web-search"
        const val WEB_FETCH = "web-fetch"
        const val CURRENT_TIME = "current-time"
        const val DEVICE_INFO = "device-info"
        const val ASK_USER = "ask-user"
        const val COMMENT = "comment"

        private const val FETCH_UA = "Spettro Agent/1.0"
        private const val SEARCH_BODY_CAP = 512 * 1024
        private const val FETCH_BODY_CAP = 2 * 1024 * 1024
        private const val FETCH_TEXT_DEFAULT = 20_000
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

    private fun commentMessage(argumentsJson: String): String? =
        stringArg(argumentsJson, "message")?.take(300)

    suspend fun execute(call: ToolCallData): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (call.name) {
                WEB_SEARCH -> webSearch(call.arguments)
                WEB_FETCH -> webFetch(call.arguments)
                CURRENT_TIME -> currentTime(call.arguments)
                DEVICE_INFO -> deviceInfo()
                // The CLI echoes the message back verbatim as the result.
                COMMENT -> ToolResult(stringArg(call.arguments, "message") ?: "")
                // ask-user blocks on the person; the ViewModel intercepts it
                // before execution ever reaches the registry.
                ASK_USER -> ToolResult("error: ask-user: interactive callback not configured", isError = true)
                else -> ToolResult("Unknown tool: ${call.name}", isError = true)
            }
        } catch (e: Exception) {
            ToolResult("Tool ${call.name} failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    // --- Argument helpers ---

    private fun args(argumentsJson: String) =
        runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

    private fun stringArg(argumentsJson: String, key: String): String? =
        args(argumentsJson)?.get(key)?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun intArg(argumentsJson: String, key: String): Int? =
        args(argumentsJson)?.get(key)?.jsonPrimitive?.intOrNull

    private fun quotedArg(argumentsJson: String, key: String): String? =
        stringArg(argumentsJson, key)?.let { "“${it.take(60)}”" }

    private fun hostArg(argumentsJson: String): String? =
        stringArg(argumentsJson, "url")?.let { runCatching { URL(it).host }.getOrNull() }

    // --- web-search: the CLI's DuckDuckGo HTML scrape, plus snippets ---

    private val resultAnchor = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val resultSnippet = Regex(
        """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun webSearch(argumentsJson: String): ToolResult {
        val query = stringArg(argumentsJson, "query")
            ?: return ToolResult("web-search requires a \"query\" argument", isError = true)
        val max = (intArg(argumentsJson, "max_results") ?: 10).coerceIn(1, 25)
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", FETCH_UA).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ToolResult("search failed: HTTP ${resp.code}", isError = true)
            val body = resp.body.byteStream().readNBytesCompat(SEARCH_BODY_CAP).decodeToString()
            val snippets = resultSnippet.findAll(body).map { htmlToText(it.groupValues[1]) }.toList()
            val lines = resultAnchor.findAll(body).take(max).mapIndexedNotNull { i, m ->
                val dest = resolveDuckDuckGoUrl(m.groupValues[1]) ?: return@mapIndexedNotNull null
                val title = htmlToText(m.groupValues[2])
                val snippet = snippets.getOrNull(i)?.takeIf { it.isNotBlank() }
                buildString {
                    append(title).append(" — ").append(dest)
                    if (snippet != null) append("\n  ").append(snippet.take(300))
                }
            }.toList()
            if (lines.isEmpty()) return ToolResult("No results found for: $query")
            return ToolResult(lines.joinToString("\n"))
        }
    }

    /** Unwraps DDG's `/l/?uddg=<encoded>` redirect to the real destination. */
    private fun resolveDuckDuckGoUrl(href: String): String? {
        val raw = htmlToText(href)
        if (!raw.contains("uddg=")) {
            return raw.takeIf { it.startsWith("http") }
        }
        val encoded = raw.substringAfter("uddg=").substringBefore("&")
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    // --- web-fetch ---

    private fun webFetch(argumentsJson: String): ToolResult {
        val url = stringArg(argumentsJson, "url")
            ?: return ToolResult("web-fetch requires a \"url\" argument", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult("only http(s) URLs can be fetched", isError = true)
        }
        val maxLen = (intArg(argumentsJson, "max_length") ?: FETCH_TEXT_DEFAULT).coerceIn(500, 100_000)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", FETCH_UA)
            .header("Accept", "text/html, text/plain, application/json, application/xml;q=0.9, */*;q=0.5")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ToolResult("fetch failed: HTTP ${resp.code}", isError = true)
            val type = resp.header("Content-Type").orEmpty().lowercase()
            if (listOf("image/", "video/", "audio/", "application/octet-stream").any { type.startsWith(it) }) {
                return ToolResult("cannot read binary content ($type)", isError = true)
            }
            val body = resp.body.byteStream().readNBytesCompat(FETCH_BODY_CAP).decodeToString()
            val text = if (type.contains("html") || body.trimStart().startsWith("<")) htmlToText(body) else body
            val out = text.trim()
            if (out.isEmpty()) return ToolResult("the page had no readable text", isError = true)
            return ToolResult(if (out.length > maxLen) out.take(maxLen) + "\n[truncated]" else out)
        }
    }

    // --- current-time ---

    private fun currentTime(argumentsJson: String): ToolResult {
        val zone = stringArg(argumentsJson, "timezone")?.let {
            runCatching { ZoneId.of(it) }.getOrNull()
                ?: return ToolResult("unknown timezone: $it", isError = true)
        } ?: ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val fmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm:ss zzz", Locale.getDefault())
        val utc = now.withZoneSameInstant(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
        return ToolResult("${now.format(fmt)} (${zone.id})\n$utc")
    }

    // --- device-info ---

    private fun deviceInfo(): ToolResult {
        val lines = mutableListOf<String>()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                lines += "Battery: ${level * 100 / scale}%" + if (charging) " (charging)" else ""
            }
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        lines += "Network: " + when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "connected"
        }
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        lines += "Locale: ${Locale.getDefault().toLanguageTag()}"
        lines += "Timezone: ${ZoneId.systemDefault().id}"
        return ToolResult(lines.joinToString("\n"))
    }

    // --- HTML helpers ---

    private val scriptOrStyle = Regex(
        """<(script|style|noscript)[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tag = Regex("<[^>]+>")

    private fun htmlToText(html: String): String =
        html.replace(scriptOrStyle, " ")
            .replace(Regex("(?i)<br[^>]*>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
            .replace(tag, " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .lines().joinToString("\n") { it.trim().replace(Regex(" {2,}"), " ") }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}

/** Reads up to [cap] bytes; InputStream.readNBytes needs API 33 on some paths. */
private fun java.io.InputStream.readNBytesCompat(cap: Int): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0
    while (total < cap) {
        val n = read(chunk, 0, minOf(chunk.size, cap - total))
        if (n < 0) break
        buf.write(chunk, 0, n)
        total += n
    }
    return buf.toByteArray()
}
