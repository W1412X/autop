package com.example.autop.llm.anthropic

import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class AnthropicStreamParserTest {
    @Test
    fun `input tokens include cache read and write`() {
        val parser = AnthropicStreamParser(listOf("ctx"))
        val events = mutableListOf<LlmStreamEvent>()
        events += parser.feed("message_start", obj(USAGE_START))
        events +=
            parser.feed("message_delta", obj("""{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}"""))
        events += parser.finishIfNeeded()

        val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
        assertThat(checkNotNull(completed.usage).inputTokens).isEqualTo(5 + 2 + 1)
    }

    @Test
    fun `text stream assembles assistant message with usage`() {
        val parser = AnthropicStreamParser(listOf("ctx"))
        val events = mutableListOf<LlmStreamEvent>()
        events += parser.feed("message_start", obj(USAGE_START))
        events += parser.feed("content_block_start", obj("""{"index":0,"content_block":{"type":"text"}}"""))
        val first = obj("""{"index":0,"delta":{"type":"text_delta","text":"he"}}""")
        val second = obj("""{"index":0,"delta":{"type":"text_delta","text":"llo"}}""")
        events += parser.feed("content_block_delta", first)
        events += parser.feed("content_block_delta", second)
        val delta = obj("""{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""")
        events += parser.feed("message_delta", delta)
        events += parser.feed("message_stop", obj("{}"))

        val completed = events.last() as LlmStreamEvent.Completed
        assertThat(events.filterIsInstance<LlmStreamEvent.TextDelta>().map { it.text })
            .containsExactly("he", "llo")
            .inOrder()
        assertThat(completed.message.blocks).containsExactly(Block.Text("hello"))
        assertThat(completed.stopReason).isEqualTo(StopReason.END_TURN)
        assertThat(completed.usage).isEqualTo(Usage(8, 7, 2, 1))
    }

    @Test
    fun `tool call fragments assemble into tool call block`() {
        val parser = AnthropicStreamParser(emptyList())
        val events = mutableListOf<LlmStreamEvent>()
        val start = obj("""{"index":0,"content_block":{"type":"tool_use","id":"c1","name":"echo"}}""")
        val open = obj("""{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"x\":"}}""")
        val empty = obj("""{"index":0,"delta":{"type":"input_json_delta","partial_json":""}}""")
        val close = obj("""{"index":0,"delta":{"type":"input_json_delta","partial_json":"1}"}}""")
        events += parser.feed("content_block_start", start)
        events += parser.feed("content_block_delta", open)
        events += parser.feed("content_block_delta", empty)
        events += parser.feed("content_block_delta", close)
        val delta = obj("""{"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":3}}""")
        events += parser.feed("message_delta", delta)
        events += parser.feed("message_stop", obj("{}"))

        assertThat(events.filterIsInstance<LlmStreamEvent.ToolCallArgsDelta>()).hasSize(2)
        val completed = events.last() as LlmStreamEvent.Completed
        assertThat(completed.message.blocks).containsExactly(Block.ToolCall("c1", "echo", """{"x":1}"""))
        assertThat(completed.stopReason).isEqualTo(StopReason.TOOL_USE)
        assertThat(events.filterIsInstance<LlmStreamEvent.ToolCallStarted>())
            .containsExactly(LlmStreamEvent.ToolCallStarted(0, "c1", "echo"))
    }

    @Test
    fun `thinking blocks round-trip with signature and redacted`() {
        val parser = AnthropicStreamParser(emptyList())
        val events = mutableListOf<LlmStreamEvent>()
        events += parser.feed("content_block_start", obj("""{"index":0,"content_block":{"type":"thinking"}}"""))
        val think = obj("""{"index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}""")
        val sign = obj("""{"index":0,"delta":{"type":"signature_delta","signature":"sig"}}""")
        events += parser.feed("content_block_delta", think)
        events += parser.feed("content_block_delta", sign)
        val redacted = obj("""{"index":1,"content_block":{"type":"redacted_thinking","data":"red"}}""")
        events += parser.feed("content_block_start", redacted)
        events += parser.feed("message_stop", obj("{}"))

        val completed = events.last() as LlmStreamEvent.Completed
        assertThat(events.filterIsInstance<LlmStreamEvent.ThinkingDelta>().map { it.text }).containsExactly("plan")
        assertThat(completed.message.blocks)
            .containsExactly(
                Block.Thinking("plan", signature = "sig"),
                Block.Thinking("", redacted = "red"),
            )
        assertThat(completed.stopReason).isEqualTo(StopReason.END_TURN)
    }

    @Test
    fun `finishIfNeeded completes once and infers tool use stop reason`() {
        val parser = AnthropicStreamParser(emptyList())
        val start = obj("""{"index":0,"content_block":{"type":"tool_use","id":"c1","name":"echo"}}""")
        parser.feed("content_block_start", start)

        val first = parser.finishIfNeeded()
        val second = parser.finishIfNeeded()

        val completed = first.last() as LlmStreamEvent.Completed
        assertThat(completed.stopReason).isEqualTo(StopReason.TOOL_USE)
        assertThat(completed.usage).isEqualTo(Usage(0, 0, 0, 0))
        assertThat(first).hasSize(2)
        assertThat(second).isEmpty()
    }

    @Test
    fun `error events map to llm error codes`() {
        val cases =
            listOf(
                "overloaded_error" to LlmErrorCodes.SERVER,
                "rate_limit_error" to LlmErrorCodes.RATE_LIMITED,
                "invalid_request_error" to LlmErrorCodes.BAD_REQUEST,
                "authentication_error" to LlmErrorCodes.AUTH,
                "unknown_error" to LlmErrorCodes.BAD_RESPONSE,
            )
        for ((type, code) in cases) {
            val parser = AnthropicStreamParser(listOf("ctx"))
            val data = obj("""{"error":{"type":"$type","message":"boom"}}""")
            val e = assertThrows(LlmException::class.java) { parser.feed("error", data) }
            assertThat(e.error.code).isEqualTo(code)
        }
    }

    @Test
    fun `context pattern turns invalid request into context exceeded`() {
        val parser = AnthropicStreamParser(listOf("too long"))
        val data = obj("""{"error":{"type":"invalid_request_error","message":"prompt is TOO LONG"}}""")

        val e = assertThrows(LlmException::class.java) { parser.feed("error", data) }

        assertThat(e.error.code).isEqualTo(LlmErrorCodes.CONTEXT_EXCEEDED)
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val USAGE_START =
            """{"message":{"usage":{"input_tokens":5,"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}}"""
    }
}
