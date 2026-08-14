package to.eyed.spettro.chat.data.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import to.eyed.spettro.chat.data.api.await
import to.eyed.spettro.chat.data.tools.ToolResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal MCP client over the Streamable HTTP transport — just the consumer
 * subset this app needs: initialize/initialized, tools/list, tools/call.
 * JSON-RPC over one POST per message; responses arrive as application/json
 * or as a text/event-stream carrying the JSON-RPC messages (parsed with the
 * same line technique as SpettroApi.streamBody). Server-initiated requests
 * are ignored — this client advertises no capabilities.
 */
class McpClient(
    @Volatile var config: McpServerConfig,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val MAX_OUTPUT_CHARS = 20_000
        val jsonMedia = "application/json".toMediaType()
    }

    private val nextId = AtomicLong(1)
    private val initLock = Mutex()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var initialized = false

    suspend fun ensureInitialized() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            handshake()
        }
    }

    private suspend fun handshake() {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "spettro-chat-android")
                put("version", "1.0")
            }
        }
        sessionId = null
        val (result, resp) = rpcWithResponse("initialize", params)
        resp.header("Mcp-Session-Id")?.let { sessionId = it }
        result ?: throw McpException("initialize returned no result")
        initialized = true
        // Fire-and-forget per spec; servers reply 202 with no body.
        notify("notifications/initialized")
    }

    suspend fun listTools(): List<McpToolDef> {
        ensureInitialized()
        val tools = mutableListOf<McpToolDef>()
        var cursor: String? = null
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = rpc("tools/list", params)?.jsonObject
                ?: throw McpException("tools/list returned no result")
            result["tools"]?.jsonArray?.forEach { el ->
                runCatching { json.decodeFromJsonElement(McpToolDef.serializer(), el) }
                    .getOrNull()?.let { tools += it }
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null && tools.size < 200)
        return tools
    }

    suspend fun callTool(name: String, argumentsJson: String): ToolResult {
        ensureInitialized()
        val arguments = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse { return ToolResult("invalid tool arguments (not a JSON object)", isError = true) }
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpc("tools/call", params)?.jsonObject
            ?: return ToolResult("the server returned no result", isError = true)
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val text = result["content"]?.jsonArray
            ?.mapNotNull { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    "resource" -> obj["resource"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }
            ?.joinToString("\n")
            .orEmpty()
        return ToolResult(text.take(MAX_OUTPUT_CHARS).ifBlank { "(empty result)" }, isError = isError)
    }

    /** Best-effort session teardown; failures are irrelevant. */
    suspend fun close() {
        val sid = sessionId ?: return
        runCatching {
            http.newCall(
                requestBuilder()
                    .header("Mcp-Session-Id", sid)
                    .delete()
                    .build(),
            ).await().close()
        }
        sessionId = null
        initialized = false
    }

    // --- Wire plumbing ---

    private fun requestBuilder(): Request.Builder {
        val b = Request.Builder()
            .url(config.url)
            .header("Accept", "application/json, text/event-stream")
        if (config.bearerToken.isNotBlank()) b.header("Authorization", "Bearer ${config.bearerToken}")
        if (config.headerName.isNotBlank()) b.header(config.headerName, config.headerValue)
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        if (initialized) b.header("MCP-Protocol-Version", PROTOCOL_VERSION)
        return b
    }

    private suspend fun rpc(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement? {
        val (result, resp) = try {
            rpcWithResponse(method, params)
        } catch (e: McpSessionExpired) {
            // 404 with a session id: the server dropped us; re-handshake once.
            initialized = false
            initLock.withLock { if (!initialized) handshake() }
            rpcWithResponse(method, params)
        }
        resp.close()
        return result
    }

    private class McpSessionExpired : Exception()

    /** Sends one request and resolves its JSON-RPC response from either body form. */
    private suspend fun rpcWithResponse(
        method: String,
        params: JsonObject,
    ): Pair<kotlinx.serialization.json.JsonElement?, Response> {
        val id = nextId.getAndIncrement()
        val body = json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = id, method = method, params = params),
        )
        val resp = http.newCall(requestBuilder().post(body.toRequestBody(jsonMedia)).build()).await()
        if (resp.code == 404 && sessionId != null) {
            resp.close()
            throw McpSessionExpired()
        }
        if (!resp.isSuccessful) {
            val detail = resp.body.string().take(200)
            resp.close()
            throw McpException("HTTP ${resp.code} from ${config.name}${if (detail.isBlank()) "" else ": $detail"}")
        }
        val contentType = resp.header("Content-Type").orEmpty()
        val rpcResponse: JsonRpcResponse? = if (contentType.startsWith("text/event-stream")) {
            resp.use { r ->
                val source = r.body.source()
                var found: JsonRpcResponse? = null
                while (found == null) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    val msg = runCatching {
                        json.decodeFromString(JsonRpcResponse.serializer(), payload)
                    }.getOrNull() ?: continue
                    // Ignore notifications/requests from the server; wait for
                    // the reply that matches our id.
                    if (msg.method == null && msg.id == id) found = msg
                }
                found
            }
        } else {
            val text = resp.body.string()
            if (text.isBlank()) null
            else runCatching { json.decodeFromString(JsonRpcResponse.serializer(), text) }.getOrNull()
        }
        rpcResponse?.error?.let { throw McpException("${config.name}: ${it.message} (code ${it.code})") }
        return (rpcResponse?.result) to resp
    }

    private suspend fun notify(method: String) {
        val body = json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = null, method = method),
        )
        runCatching {
            http.newCall(requestBuilder().post(body.toRequestBody(jsonMedia)).build()).await().close()
        }
    }
}
