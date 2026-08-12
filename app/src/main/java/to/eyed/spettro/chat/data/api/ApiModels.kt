package to.eyed.spettro.chat.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire DTOs for api.spettro.app, mirroring the CLI's internal/spettro/client.go.

@Serializable
data class InitiateRequest(@SerialName("session_id") val sessionId: String)

@Serializable
data class InitiateResponse(@SerialName("browser_url") val browserUrl: String = "")

/** Poll status: "pending" | "complete" | "expired". The ep_ key arrives exactly once. */
@Serializable
data class PollResponse(val status: String = "", @SerialName("api_key") val apiKey: String = "")

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

@Serializable
data class StreamDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
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
data class CompletionMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class CompletionChoice(val message: CompletionMessage = CompletionMessage())

@Serializable
data class CompletionResponse(
    val choices: List<CompletionChoice> = emptyList(),
    val usage: UsageInfo? = null,
)

/** One outgoing chat message; images are data URLs for vision models. */
data class OutgoingMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
)

/** Incremental events from a streamed completion. */
sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    data class Reasoning(val delta: String) : ChatEvent
    data class RateLimited(val retryAfterSeconds: Int) : ChatEvent
    data class Usage(val info: UsageInfo) : ChatEvent
    data object Done : ChatEvent
}

/** 401 from the backend: the ep_ key is no longer valid, re-run login. */
class UnauthorizedException : Exception("your Spettro session is no longer valid — please sign in again")

class ApiException(val statusCode: Int, message: String) : Exception(message)
