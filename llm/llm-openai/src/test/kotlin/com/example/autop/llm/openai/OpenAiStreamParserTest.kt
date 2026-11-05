package com.example.autop.llm.openai

import com.example.autop.llm.Block
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.StopReason
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class OpenAiStreamParserTest {
    private val parser = OpenAiStreamParser("reasoning_content")

    @Test
    fun `text deltas are collected into completed message`() {
        val events = mutableListOf<LlmStreamEvent>()
        events += parser.feed(chunk("""{"choices": [{"delta": {"content": "he"}}]}"""))
        events += parser.feed(chunk("""{"choices": [{"delta": {"content": "llo"}}]}"""))
        val completed = parser.finish()

        assertThat(events).containsExactly(LlmStreamEvent.TextDelta("he"), LlmStreamEvent.TextDelta("llo"))
        assertThat(completed.message.blocks).containsExactly(Block.Text("hello"))
        assertThat(completed.stopReason).isEqualTo(StopReason.END_TURN)
    }

    @Test
    fun `tool call args split across chunks are concatenated`() {
        val events = mutableListOf<LlmStreamEvent>()
        events +=
            parser.feed(
                chunk(
                    """{"choices": [{"delta": {"tool_calls": [
                        |{"index": 0, "id": "call_1", "function": {"name": "echo", "arguments": ""}}
                        |]}}]}""",
                ),
            )
        events +=
            parser.feed(
                chunk(
                    """{"choices": [{"delta": {"tool_calls": [
                        |{"index": 0, "function": {"arguments": "{\"x\":"}}
                        |]}}]}""",
                ),
            )
        events +=
            parser.feed(
                chunk("""{"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "1}"}}]}}]}"""),
            )
        val completed = parser.finish()

        assertThat(events.filterIsInstance<LlmStreamEvent.ToolCallArgsDelta>()).hasSize(2)
        val call = completed.message.blocks.single() as Block.ToolCall
        assertThat(call.argsJson).isEqualTo("""{"x":1}""")
        assertThat(completed.stopReason).isEqualTo(StopReason.TOOL_USE)
    }

    @Test
    fun `missing tool call id is generated with index and counter`() {
        val started =
            parser
                .feed(
                    chunk(
                        """{"choices": [{"delta": {"tool_calls": [
                            |{"index": 3, "function": {"name": "look", "arguments": "{}"}}
                            |]}}]}""",
                    ),
                ).filterIsInstance<LlmStreamEvent.ToolCallStarted>()

        assertThat(started.single().id).isEqualTo("call_3_0")
        assertThat(started.single().index).isEqualTo(3)
    }

    @Test
    fun `usage chunk is reported and carried into completed`() {
        val events =
            parser.feed(
                chunk(
                    """{"choices": [], "usage": {"prompt_tokens": 12, "completion_tokens": 5,
                      |"prompt_tokens_details": {"cached_tokens": 4}}}""",
                ),
            )

        assertThat(events).containsExactly(LlmStreamEvent.UsageReported(usageFixture()))
        val completed = parser.finish()
        assertThat(completed.usage).isEqualTo(usageFixture())
    }

    @Test
    fun `finish reason maps to stop reason`() {
        parser.feed(chunk("""{"choices": [{"delta": {"content": "x"}, "finish_reason": "length"}]}"""))

        assertThat(parser.finish().stopReason).isEqualTo(StopReason.MAX_TOKENS)
    }

    @Test
    fun `thinking deltas are collected into completed message`() {
        val events = mutableListOf<LlmStreamEvent>()
        events += parser.feed(chunk("""{"choices": [{"delta": {"reasoning_content": "w"}}]}"""))
        events += parser.feed(chunk("""{"choices": [{"delta": {"reasoning_content": "hy", "content": "ok"}}]}"""))

        assertThat(events)
            .containsExactly(
                LlmStreamEvent.ThinkingDelta("w"),
                LlmStreamEvent.ThinkingDelta("hy"),
                LlmStreamEvent.TextDelta("ok"),
            )
        assertThat(parser.finish().message.blocks)
            .containsExactly(Block.Thinking("why"), Block.Text("ok"))
            .inOrder()
    }

    @Test
    fun `no thinking block when reasoning is absent`() {
        parser.feed(chunk("""{"choices": [{"delta": {"content": "ok"}}]}"""))

        assertThat(parser.finish().message.blocks).containsExactly(Block.Text("ok"))
    }

    private fun chunk(text: String): JsonObject = Json.parseToJsonElement(text.trimMargin()).jsonObject

    private fun usageFixture() =
        com.example.autop.llm
            .Usage(inputTokens = 12, outputTokens = 5, cacheReadTokens = 4)
}
