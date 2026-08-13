package to.eyed.spettro.chat.data.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * web-search and web-fetch. Search is the CLI's DuckDuckGo HTML scrape
 * (internal/agent/llm_runtime_ext.go) plus result snippets; fetch strips a
 * page down to readable text.
 */
internal class WebTools {
    companion object {
        // Same UA as the CLI's agent fetches, so DDG treats both alike.
        private const val FETCH_UA = "Spettro Agent/1.0"
        private const val SEARCH_BODY_CAP = 512 * 1024
        private const val FETCH_BODY_CAP = 2 * 1024 * 1024
        private const val FETCH_TEXT_DEFAULT = 20_000
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val resultAnchor = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val resultSnippet = Regex(
        """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun search(argumentsJson: String): ToolResult {
        val query = ToolArgs.string(argumentsJson, "query")
            ?: return ToolResult("web-search requires a \"query\" argument", isError = true)
        val max = (ToolArgs.int(argumentsJson, "max_results") ?: 10).coerceIn(1, 25)
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", FETCH_UA).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ToolResult("search failed: HTTP ${resp.code}", isError = true)
            val body = resp.body.byteStream().readCapped(SEARCH_BODY_CAP).decodeToString()
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

    fun fetch(argumentsJson: String): ToolResult {
        val url = ToolArgs.string(argumentsJson, "url")
            ?: return ToolResult("web-fetch requires a \"url\" argument", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult("only http(s) URLs can be fetched", isError = true)
        }
        val maxLen = (ToolArgs.int(argumentsJson, "max_length") ?: FETCH_TEXT_DEFAULT).coerceIn(500, 100_000)
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
            val body = resp.body.byteStream().readCapped(FETCH_BODY_CAP).decodeToString()
            val text = if (type.contains("html") || body.trimStart().startsWith("<")) htmlToText(body) else body
            val out = text.trim()
            if (out.isEmpty()) return ToolResult("the page had no readable text", isError = true)
            return ToolResult(if (out.length > maxLen) out.take(maxLen) + "\n[truncated]" else out)
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
private fun java.io.InputStream.readCapped(cap: Int): ByteArray {
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
