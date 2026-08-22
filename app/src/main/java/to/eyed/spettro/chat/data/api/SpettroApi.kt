package to.eyed.spettro.chat.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wire parser for the backend's OpenAI-compatible responses.
 * coerceInputValues is required: some upstream-routed models (Kimi K3,
 * SuperSmart, SuperFast) emit `"tool_calls": null` in every stream delta
 * whenever tools are offered, and without coercion that null makes the
 * whole chunk fail to decode — silently dropping every token of the reply.
 */
internal val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
}

/**
 * Client for the Spettro backend, authenticated with an ep_ API key:
 *  - GET  /v1/models           — models available on the user's plan
 *  - GET  /v1/account          — plan, status, and credit usage
 *  - POST /v1/chat/completions — OpenAI-compatible inference (SSE streaming)
 *
 * Sign-in (Clerk + key minting) lives in [SpettroWebApi].
 */
class SpettroApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.spettro.app"
        const val PRICING_URL = "https://spettro.app/pricing"
        const val USER_AGENT = "SpettroChat/1.0 (Android)"
        // Backend overflow bucket worst-case refill (6s) + 1s margin.
        const val DEFAULT_RATE_LIMIT_RETRY_S = 7
    }

    private val json = wireJson
    private val jsonMedia = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Streaming needs an unbounded read timeout between tokens.
    private val streamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // --- Authenticated REST ---

    private fun authedRequest(path: String): Request {
        val key = apiKeyProvider() ?: throw UnauthorizedException()
        return Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $key")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
    }

    suspend fun listModels(): List<ModelInfo> {
        client.newCall(authedRequest("/v1/models")).await().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw ApiException(resp.code, "could not list models (HTTP ${resp.code})")
            // Server order matters: data[0] is the plan's default model.
            return json.decodeFromString(ModelsResponse.serializer(), resp.body.string()).data
        }
    }

    suspend fun account(): Account {
        client.newCall(authedRequest("/v1/account")).await().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw ApiException(resp.code, "could not load account (HTTP ${resp.code})")
            return json.decodeFromString(Account.serializer(), resp.body.string())
        }
    }

    // --- Chat completions ---

    private fun chatBody(
        model: String,
        messages: List<OutgoingMessage>,
        reasoningEffort: String?,
        stream: Boolean,
        tools: List<ToolSpec>,
    ): String {
        val obj = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                for (m in messages) {
                    add(
                        buildJsonObject {
                            put("role", m.role)
                            m.toolCallId?.let { put("tool_call_id", it) }
                            if (m.toolCalls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    for (c in m.toolCalls) {
                                        add(
                                            buildJsonObject {
                                                put("id", c.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", c.name)
                                                    put("arguments", c.arguments)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            if (m.imageDataUrls.isEmpty()) {
                                if (m.text.isNotEmpty() || m.toolCalls.isEmpty()) put("content", m.text)
                            } else {
                                put(
                                    "content",
                                    buildJsonArray {
                                        if (m.text.isNotBlank()) {
                                            add(buildJsonObject { put("type", "text"); put("text", m.text) })
                                        }
                                        for (url in m.imageDataUrls) {
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    putJsonObject("image_url") { put("url", url) }
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (t in tools) {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", json.parseToJsonElement(t.parametersJson))
                                    put("strict", false)
                                }
                            },
                        )
                    }
                }
                put("tool_choice", "auto")
            }
            if (!reasoningEffort.isNullOrBlank()) put("reasoning_effort", reasoningEffort)
            if (stream) {
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
            }
        }
        return obj.toString()
    }

    /**
     * Streams a completion. Spettro overflow-tier 429s are waited out
     * transparently (emitting [ChatEvent.RateLimited] before each wait), like
     * the CLI. If streaming setup fails for another reason, retries once
     * non-streamed before surfacing the error.
     */
    fun chatStream(
        model: String,
        messages: List<OutgoingMessage>,
        reasoningEffort: String? = null,
        tools: List<ToolSpec> = emptyList(),
    ): Flow<ChatEvent> = flow {
        val key = apiKeyProvider() ?: throw UnauthorizedException()
        while (true) {
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/event-stream")
                .post(chatBody(model, messages, reasoningEffort, stream = true, tools = tools).toRequestBody(jsonMedia))
                .build()
            val resp = streamClient.newCall(req).await()
            if (resp.code == 429) {
                val retry = parseRetryAfter(resp)
                resp.close()
                emit(ChatEvent.RateLimited(retry))
                delay(retry * 1000L)
                continue
            }
            if (resp.code == 401) {
                resp.close()
                throw UnauthorizedException()
            }
            if (!resp.isSuccessful) {
                val errBody = resp.body.string().take(512)
                resp.close()
                // Retry once without streaming so a turn never dies just
                // because live tokens were unavailable.
                emitNonStreamed(model, messages, reasoningEffort, tools, ApiException(resp.code, errBody))
                return@flow
            }
            resp.use { streamBody(it) }
            emit(ChatEvent.Done)
            return@flow
        }
    }.flowOn(Dispatchers.IO)

    /** Accumulates one streamed tool call; argument fragments are joined by index. */
    private class ToolCallAcc {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.streamBody(resp: Response) {
        val source = resp.body.source()
        // Tool calls stream as fragments keyed by index; they are only
        // dispatched once the stream ends and every argument is complete.
        val toolAcc = sortedMapOf<Int, ToolCallAcc>()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            val chunk = runCatching { json.decodeFromString(StreamChunk.serializer(), payload) }.getOrNull() ?: continue
            chunk.usage?.let { emit(ChatEvent.Usage(it)) }
            val delta = chunk.choices.firstOrNull()?.delta ?: continue
            delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Reasoning(it)) }
            delta.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
            for (tc in delta.toolCalls) {
                val acc = toolAcc.getOrPut(tc.index) { ToolCallAcc() }
                tc.id?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
                tc.function.name?.takeIf { it.isNotEmpty() }?.let {
                    if (acc.name.isEmpty()) emit(ChatEvent.ToolCallStart(it))
                    acc.name += it
                }
                tc.function.arguments?.let { acc.args.append(it) }
            }
        }
        for (acc in toolAcc.values) {
            if (acc.name.isEmpty()) continue
            emit(ChatEvent.ToolCall(ToolCallData(acc.id, acc.name, acc.args.toString())))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.emitNonStreamed(
        model: String,
        messages: List<OutgoingMessage>,
        reasoningEffort: String?,
        tools: List<ToolSpec>,
        originalError: Exception,
    ) {
        val key = apiKeyProvider() ?: throw UnauthorizedException()
        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("User-Agent", USER_AGENT)
            .post(chatBody(model, messages, reasoningEffort, stream = false, tools = tools).toRequestBody(jsonMedia))
            .build()
        val resp = try {
            client.newCall(req).await()
        } catch (_: Exception) {
            throw originalError
        }
        resp.use {
            if (it.code == 401) throw UnauthorizedException()
            if (!it.isSuccessful) throw originalError
            val out = json.decodeFromString(CompletionResponse.serializer(), it.body.string())
            val msg = out.choices.firstOrNull()?.message
            msg?.reasoningContent?.takeIf { r -> r.isNotEmpty() }?.let { r -> emit(ChatEvent.Reasoning(r)) }
            msg?.content?.takeIf { c -> c.isNotEmpty() }?.let { c -> emit(ChatEvent.Text(c)) }
            msg?.toolCalls?.forEach { tc ->
                if (tc.function.name.isNotEmpty()) {
                    emit(ChatEvent.ToolCall(ToolCallData(tc.id, tc.function.name, tc.function.arguments)))
                }
            }
            out.usage?.let { u -> emit(ChatEvent.Usage(u)) }
            emit(ChatEvent.Done)
        }
    }

    private fun parseRetryAfter(resp: Response): Int {
        val header = resp.header("Retry-After") ?: return DEFAULT_RATE_LIMIT_RETRY_S
        header.trim().toIntOrNull()?.let { return it.coerceIn(1, 120) }
        return DEFAULT_RATE_LIMIT_RETRY_S
    }
}

/** Bridges OkHttp's callback API into coroutines with proper cancellation. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation { runCatching { cancel() } }
}
