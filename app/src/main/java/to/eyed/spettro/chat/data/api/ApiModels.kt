package to.eyed.spettro.chat.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire DTOs for api.spettro.app, mirroring the CLI's internal/spettro/client.go.

/** Minted ep_ key from spettro.app/api/keys/generate; the raw key arrives exactly once. */
@Serializable
data class ApiKeyGrant(
    val key: String = "",
    val id: String = "",
    val label: String = "",
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("owned_by") val ownedBy: String = "",
    @SerialName("context_window") val contextWindow: Int = 0,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
)

@Serializable
data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class Account(
    val email: String = "",
    val plan: String = "",
    @SerialName("plan_status") val planStatus: String = "",
    @SerialName("credits_used") val creditsUsed: Double = 0.0,
    @SerialName("credit_limit") val creditLimit: Double = 0.0,
    @SerialName("remaining_credits") val remainingCredits: Double? = null,
) {
    val planOrFree: String get() = plan.ifBlank { "free" }.lowercase()
    val remaining: Double get() = remainingCredits ?: (creditLimit - creditsUsed)
}

// --- Chat completions (OpenAI-compatible SSE) ---

/**
 * A tool offered to the model, in the standard OpenAI function-tool shape.
 * [parametersJson] is the raw JSON Schema for the arguments object.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/** A tool invocation requested by the model; [arguments] is a JSON-encoded string. */
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class StreamToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

/** One fragment of a streamed tool call; fragments are joined by [index]. */
@Serializable
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: StreamToolCallFunction = StreamToolCallFunction(),
)

@Serializable
data class StreamDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamToolCallDelta> = emptyList(),
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class UsageInfo(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: UsageInfo? = null,
)

// Non-streaming fallback response
@Serializable
data class WireToolCallFunction(val name: String = "", val arguments: String = "")

@Serializable
data class WireToolCall(
    val id: String = "",
    val type: String = "function",
    val function: WireToolCallFunction = WireToolCallFunction(),
)

@Serializable
data class CompletionMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall> = emptyList(),
)

@Serializable
data class CompletionChoice(val message: CompletionMessage = CompletionMessage())

@Serializable
data class CompletionResponse(
    val choices: List<CompletionChoice> = emptyList(),
    val usage: UsageInfo? = null,
)

/**
 * One outgoing chat message; images are data URLs for vision models.
 * An assistant message may carry [toolCalls]; a `role:"tool"` result names
 * the call it answers via [toolCallId].
 */
data class OutgoingMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
    val toolCalls: List<ToolCallData> = emptyList(),
    val toolCallId: String? = null,
)

/** Incremental events from a streamed completion. */
sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    data class Reasoning(val delta: String) : ChatEvent
    /** The model began emitting a tool call; arguments are still streaming. */
    data class ToolCallStart(val name: String) : ChatEvent
    /** A fully-assembled tool call. All calls of the turn arrive before [Done]. */
    data class ToolCall(val call: ToolCallData) : ChatEvent
    data class RateLimited(val retryAfterSeconds: Int) : ChatEvent
    data class Usage(val info: UsageInfo) : ChatEvent
    data object Done : ChatEvent
}

/** 401 from the backend: the ep_ key is no longer valid, re-run login. */
class UnauthorizedException : Exception("your Spettro session is no longer valid — please sign in again")

class ApiException(val statusCode: Int, message: String) : Exception(message)
