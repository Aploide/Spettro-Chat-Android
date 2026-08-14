package to.eyed.spettro.chat.vm

import to.eyed.spettro.chat.data.store.StoredMessage

/** Live view of one tool invocation, shown in the chat while a turn runs. */
data class ToolRunUi(
    val name: String,
    val label: String,
    val running: Boolean,
    val failed: Boolean = false,
    /** The full tool response, inspectable by the user; empty while running. */
    val output: String = "",
)

sealed interface StreamState {
    data object Idle : StreamState
    /** Request sent, nothing received yet (or only reasoning/tools so far). */
    data class Thinking(
        val reasoning: String = "",
        val tools: List<ToolRunUi> = emptyList(),
    ) : StreamState
    data class Streaming(
        val text: String,
        val reasoning: String,
        val tools: List<ToolRunUi> = emptyList(),
    ) : StreamState
    data class RateLimited(val retryAfterSeconds: Int) : StreamState
    /** The conversation is being summarized to free up context. */
    data object Compacting : StreamState
    data class Error(val message: String) : StreamState
}

/**
 * Rough context accounting so the app can stop a chat before it overflows
 * the model's window: ~4 chars per token for text, a flat estimate per
 * attached image.
 */
object ContextEstimator {
    private const val CHARS_PER_TOKEN = 4
    private const val TOKENS_PER_IMAGE = 1600

    /** Block sending once the history uses this share of the window. */
    const val BLOCK_RATIO = 0.85f

    /**
     * Compact automatically once a finished turn leaves the history above
     * this share, so users normally never reach the [BLOCK_RATIO] hard stop.
     */
    const val AUTO_COMPACT_RATIO = 0.75f

    fun estimateTokens(messages: List<StoredMessage>): Int = messages.sumOf {
        (it.content.length + it.thinking.length + it.files.sumOf { f -> f.text.length }) / CHARS_PER_TOKEN +
            it.images.size * TOKENS_PER_IMAGE
    }

    /** Used share of the model's window; 0 when the window is unknown. */
    fun usedRatio(messages: List<StoredMessage>, contextWindow: Int): Float {
        if (contextWindow <= 0) return 0f
        return estimateTokens(messages).toFloat() / contextWindow
    }

    fun isNearLimit(messages: List<StoredMessage>, contextWindow: Int): Boolean =
        usedRatio(messages, contextWindow) >= BLOCK_RATIO

    fun shouldAutoCompact(messages: List<StoredMessage>, contextWindow: Int): Boolean =
        usedRatio(messages, contextWindow) >= AUTO_COMPACT_RATIO
}
