package to.eyed.spettro.chat.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Parses real response payloads captured from the upstreams. The upstream
 * chunks are the regression case: they carry `"tool_calls": null` (and
 * `"logprobs": null`) whenever tools are offered, which the previous parser
 * config rejected — every chunk of the reply was silently skipped.
 */
class WireParsingTest {
    @Test
    fun `upstream stream delta with null tool_calls decodes`() {
        val payload = """{"id": "chatcmpl-1787429964.457218", "object": "chat.completion.chunk", "created": 1787429964, "model": "kimi-k3", "choices": [{"index": 0, "delta": {"role": "assistant", "content": "Hi!", "tool_calls": null}, "logprobs": null}]}"""
        val chunk = wireJson.decodeFromString(StreamChunk.serializer(), payload)
        assertEquals("Hi!", chunk.choices.single().delta.content)
        assertEquals(emptyList<StreamToolCallDelta>(), chunk.choices.single().delta.toolCalls)
    }

    @Test
    fun `upstream reasoning delta with null tool_calls decodes`() {
        val payload = """{"id": "chatcmpl-1787429964.309824", "object": "chat.completion.chunk", "created": 1787429964, "model": "kimi-k3", "choices": [{"index": 0, "delta": {"role": "assistant", "content": "", "tool_calls": null, "reasoning_content": "The"}, "logprobs": null}]}"""
        val chunk = wireJson.decodeFromString(StreamChunk.serializer(), payload)
        assertEquals("The", chunk.choices.single().delta.reasoningContent)
    }

    @Test
    fun `upstream usage chunk decodes`() {
        val payload = """{"id": "chatcmpl-1787429964.466368", "object": "chat.completion.chunk", "created": 1787429964, "model": "kimi-k3", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 146, "completion_tokens": 15, "reasoning_tokens": 11, "total_tokens": 161, "prompt_tokens_details": {"cached_tokens": 127}, "cost": 0.00018974999999999998}}"""
        val chunk = wireJson.decodeFromString(StreamChunk.serializer(), payload)
        assertNotNull(chunk.usage)
        assertEquals(161, chunk.usage!!.totalTokens)
    }

    @Test
    fun `non-stream message with null tool_calls decodes`() {
        val payload = """{"choices": [{"message": {"role": "assistant", "content": "Hi!", "tool_calls": null}}], "usage": {"prompt_tokens": 42, "completion_tokens": 5, "total_tokens": 47}}"""
        val resp = wireJson.decodeFromString(CompletionResponse.serializer(), payload)
        assertEquals("Hi!", resp.choices.single().message.content)
        assertEquals(emptyList<WireToolCall>(), resp.choices.single().message.toolCalls)
    }
}
