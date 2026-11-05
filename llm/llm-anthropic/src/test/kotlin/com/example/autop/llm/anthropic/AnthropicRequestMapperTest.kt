package com.example.autop.llm.anthropic

import com.example.autop.llm.Block
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AnthropicRequestMapperTest {
    private val images = FakeImageLoader()
    private val mapper = AnthropicRequestMapper(images, FakePrompts())

    @Test
    fun `full request maps to anthropic wire format`() =
        runTest {
            val request =
                LlmRequest(
                    system = listOf(SystemPart("sys", cacheBreakpoint = true)),
                    messages =
                        listOf(
                            Msg(Role.USER, listOf(Block.Text("hi"))),
                            Msg(
                                Role.ASSISTANT,
                                listOf(Block.Text("calling"), Block.ToolCall("call_1", "echo", """{"x":1}""")),
                            ),
                            Msg(Role.TOOL, listOf(Block.ToolResult("call_1", listOf(Block.Text("result")), false))),
                        ),
                    tools = listOf(ToolSchema("echo", "Echo back", parse("""{"type": "object"}"""))),
                    toolChoice = ToolChoice.REQUIRED,
                    maxOutputTokens = 4096,
                    thinkingBudgetTokens = 1024,
                    cacheBreakpointMessageIndex = 2,
                )

            val actual = mapper.mapChatRequest(request, quirks(), model = "m")

            assertThat(actual).isEqualTo(parse(FULL_EXPECTED))
        }

    @Test
    fun `adjacent same role messages merge and leading user is prepended`() =
        runTest {
            val request =
                requestOf(
                    Msg(Role.ASSISTANT, listOf(Block.Text("a"))),
                    Msg(Role.ASSISTANT, listOf(Block.Text("b"))),
                    Msg(Role.USER, listOf(Block.Text("c"))),
                )

            val messages = mapper.mapChatRequest(request, quirks(), model = "m")["messages"]!!.jsonArray

            assertThat(messages).hasSize(3)
            val leading = messages[0].jsonObject
            assertThat(leading["role"]!!.jsonPrimitive.content).isEqualTo("user")
            val leadingText =
                leading["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            assertThat(leadingText).isEqualTo("Please respond.")
            val merged = messages[1].jsonObject
            assertThat(merged["role"]!!.jsonPrimitive.content).isEqualTo("assistant")
            assertThat(merged["content"]!!.jsonArray).hasSize(2)
        }

    @Test
    fun `tool results become tool_result blocks in a user message`() =
        runTest {
            val request = requestOf(Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("boom")), true))))

            val messages = mapper.mapChatRequest(request, quirks(), model = "m")["messages"]!!.jsonArray
            val message = messages.single().jsonObject
            val block = message["content"]!!.jsonArray.single().jsonObject

            assertThat(message["role"]!!.jsonPrimitive.content).isEqualTo("user")
            assertThat(block["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
            assertThat(block["tool_use_id"]!!.jsonPrimitive.content).isEqualTo("c1")
            assertThat(block["is_error"].toString()).isEqualTo("true")
        }

    @Test
    fun `thinking and tool_use blocks map with signature and empty input fallback`() =
        runTest {
            val request =
                requestOf(
                    Msg(
                        Role.ASSISTANT,
                        listOf(
                            Block.Thinking("plan", signature = "sig"),
                            Block.Thinking("", redacted = "red"),
                            Block.ToolCall("c1", "echo", "not json"),
                        ),
                    ),
                )

            val messages = mapper.mapChatRequest(request, quirks(), model = "m")["messages"]!!.jsonArray
            val blocks = messages.last().jsonObject["content"]!!.jsonArray

            assertThat(blocks[0].jsonObject)
                .isEqualTo(parse("""{"type": "thinking", "thinking": "plan", "signature": "sig"}"""))
            assertThat(blocks[1].jsonObject).isEqualTo(parse("""{"type": "redacted_thinking", "data": "red"}"""))
            assertThat(blocks[2].jsonObject["input"]!!.jsonObject).isEmpty()
        }

    @Test
    fun `tool_choice none either sends none type or omits tools`() =
        runTest {
            val tools = listOf(ToolSchema("echo", "Echo back", parse("""{"type": "object"}""")))
            val base = requestOf(Msg(Role.USER, listOf(Block.Text("hi"))))
            val request = base.copy(tools = tools, toolChoice = ToolChoice.NONE)

            val native = mapper.mapChatRequest(request, quirks(), model = "m")
            val omitted =
                mapper.mapChatRequest(request, quirks(toolChoiceNone = ToolChoiceNoneMode.OMIT_TOOLS), model = "m")

            assertThat(native["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("none")
            assertThat(omitted.containsKey("tools")).isFalse()
            assertThat(omitted.containsKey("tool_choice")).isFalse()
        }

    @Test
    fun `thinking budget replaces temperature`() =
        runTest {
            val base = requestOf(Msg(Role.USER, listOf(Block.Text("hi")))).copy(temperature = 0.5)

            val withBudget = mapper.mapChatRequest(base.copy(thinkingBudgetTokens = 512), quirks(), model = "m")
            val withoutBudget = mapper.mapChatRequest(base, quirks(), model = "m")

            assertThat(withBudget["thinking"]!!.jsonObject["budget_tokens"].toString()).isEqualTo("512")
            assertThat(withBudget.containsKey("temperature")).isFalse()
            assertThat(withoutBudget["temperature"].toString()).isEqualTo("0.5")
            assertThat(withoutBudget.containsKey("thinking")).isFalse()
        }

    @Test
    fun `image block uses base64 source`() =
        runTest {
            val request = requestOf(Msg(Role.USER, listOf(Block.Image("shot.png", "image/png", 100, 80))))

            val messages = mapper.mapChatRequest(request, quirks(), model = "m")["messages"]!!.jsonArray
            val source =
                messages
                    .single()
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["source"]!!
                    .jsonObject

            assertThat(source["type"]!!.jsonPrimitive.content).isEqualTo("base64")
            assertThat(source["media_type"]!!.jsonPrimitive.content).isEqualTo("image/png")
            assertThat(source["data"]!!.jsonPrimitive.content).isEqualTo("AQID")
            assertThat(images.requests).containsExactly(Triple("shot.png", "image/png", 1568))
        }

    @Test
    fun `unavailable image degrades to placeholder text block`() =
        runTest {
            val mapper = AnthropicRequestMapper(FakeImageLoader(broken = true), FakePrompts())
            val request = requestOf(Msg(Role.USER, listOf(Block.Image("gone.png", "image/png", 100, 80))))

            val messages = mapper.mapChatRequest(request, quirks(), model = "m")["messages"]!!.jsonArray
            val block =
                messages
                    .single()
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject

            assertThat(block["type"]!!.jsonPrimitive.content).isEqualTo("text")
            assertThat(block["text"]!!.jsonPrimitive.content).isEqualTo("（图片不可用）")
        }

    @Test
    fun `unavailable tool image degrades inside tool_result`() =
        runTest {
            val mapper = AnthropicRequestMapper(FakeImageLoader(broken = true), FakePrompts())
            val request =
                requestOf(
                    Msg(
                        Role.TOOL,
                        listOf(Block.ToolResult("c1", listOf(Block.Image("a.png", "image/png", 10, 10)), false)),
                    ),
                )

            val messages = mapper.mapChatRequest(request, quirks(), model = "m")["messages"]!!.jsonArray
            val toolResult =
                messages
                    .single()
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            val inner = toolResult["content"]!!.jsonArray.single().jsonObject

            assertThat(toolResult["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
            assertThat(inner["type"]!!.jsonPrimitive.content).isEqualTo("text")
            assertThat(inner["text"]!!.jsonPrimitive.content).isEqualTo("（图片不可用）")
        }

    private fun requestOf(vararg messages: Msg): LlmRequest =
        LlmRequest(system = emptyList(), messages = messages.toList(), maxOutputTokens = 4096)

    private fun quirks(toolChoiceNone: ToolChoiceNoneMode = ToolChoiceNoneMode.NATIVE): AnthropicQuirks =
        AnthropicQuirks(
            messagesPath = "/v1/messages",
            toolChoiceNone = toolChoiceNone,
            contextExceededPatterns = listOf("ctx"),
            imageMaxEdge = 1568,
        )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject

    private companion object {
        const val FULL_EXPECTED =
            """
            {
              "model": "m",
              "max_tokens": 4096,
              "system": [{"type": "text", "text": "sys", "cache_control": {"type": "ephemeral"}}],
              "messages": [
                {"role": "user", "content": [{"type": "text", "text": "hi"}]},
                {
                  "role": "assistant",
                  "content": [
                    {"type": "text", "text": "calling"},
                    {"type": "tool_use", "id": "call_1", "name": "echo", "input": {"x": 1}}
                  ]
                },
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "tool_result",
                      "tool_use_id": "call_1",
                      "content": [{"type": "text", "text": "result"}],
                      "is_error": false,
                      "cache_control": {"type": "ephemeral"}
                    }
                  ]
                }
              ],
              "tools": [{"name": "echo", "description": "Echo back", "input_schema": {"type": "object"}}],
              "tool_choice": {"type": "any"},
              "thinking": {"type": "enabled", "budget_tokens": 1024},
              "stream": true
            }
            """
    }
}
