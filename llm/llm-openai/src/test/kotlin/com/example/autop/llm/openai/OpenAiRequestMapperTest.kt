package com.example.autop.llm.openai

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

class OpenAiRequestMapperTest {
    private val images = FakeImageLoader()
    private val mapper = OpenAiRequestMapper(images, FakePrompts())

    @Test
    fun `full request maps to openai wire format`() =
        runTest {
            val request =
                LlmRequest(
                    system = listOf(SystemPart("sys A"), SystemPart("sys B")),
                    messages =
                        listOf(
                            Msg(Role.USER, listOf(Block.Text("hi"))),
                            Msg(
                                Role.ASSISTANT,
                                listOf(Block.Text("calling"), Block.ToolCall("call_1", "echo", """{"x":1}""")),
                            ),
                            Msg(Role.TOOL, listOf(Block.ToolResult("call_1", listOf(Block.Text("result")), false))),
                        ),
                    tools = listOf(ToolSchema("echo", "Echo back", buildParameters())),
                    toolChoice = ToolChoice.REQUIRED,
                    maxOutputTokens = 4096,
                    temperature = 0.2,
                )

            val actual = mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = true)

            assertThat(actual).isEqualTo(parse(FULL_EXPECTED))
        }

    @Test
    fun `single text user message uses string content`() =
        runTest {
            val request = requestOf(Msg(Role.USER, listOf(Block.Text("solo"))))

            val messages =
                mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)["messages"]!!.jsonArray

            assertThat(
                messages
                    .single()
                    .jsonObject["content"]!!
                    .jsonPrimitive.content,
            ).isEqualTo("solo")
        }

    @Test
    fun `user message with image uses base64 data url`() =
        runTest {
            val request =
                requestOf(
                    Msg(
                        Role.USER,
                        listOf(Block.Text("look"), Block.Image("shot.png", "image/png", 100, 80)),
                    ),
                )

            val messages =
                mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)["messages"]!!.jsonArray
            val parts = messages.single().jsonObject["content"]!!.jsonArray

            assertThat(parts).hasSize(2)
            val url =
                parts[1]
                    .jsonObject["image_url"]!!
                    .jsonObject["url"]!!
                    .jsonPrimitive.content
            assertThat(url).isEqualTo("data:image/png;base64,AQID")
            assertThat(images.requests).containsExactly(Triple("shot.png", "image/png", 1568))
        }

    @Test
    fun `tool result with images appends user note message`() =
        runTest {
            val request =
                requestOf(
                    Msg(
                        Role.TOOL,
                        listOf(
                            Block.ToolResult("c1", listOf(Block.Image("a.png", "image/png", 10, 10)), false),
                            Block.ToolResult("c2", listOf(Block.Text("plain")), false),
                        ),
                    ),
                )

            val messages =
                mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)["messages"]!!.jsonArray

            assertThat(messages).hasSize(3)
            assertThat(messages[0].jsonObject["tool_call_id"]!!.jsonPrimitive.content).isEqualTo("c1")
            assertThat(messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content).isEqualTo("c2")
            assertThat(messages[2].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
            val note = messages[2].jsonObject["content"]!!.jsonArray
            assertThat(note[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("images of c1")
            assertThat(note[1].jsonObject["image_url"]).isNotNull()
        }

    @Test
    fun `max tokens field quirk renames the field`() =
        runTest {
            val request = requestOf(Msg(Role.USER, listOf(Block.Text("hi"))))

            val body =
                mapper.mapChatRequest(
                    request,
                    quirks(maxTokensField = "max_completion_tokens"),
                    model = "m",
                    includeUsage = false,
                )

            assertThat(body["max_completion_tokens"]!!.jsonPrimitive.content).isEqualTo("4096")
            assertThat(body.containsKey("max_tokens")).isFalse()
        }

    @Test
    fun `thinking is not sent and empty assistant text yields null content`() =
        runTest {
            val request =
                requestOf(
                    Msg(Role.ASSISTANT, listOf(Block.Thinking("secret"), Block.ToolCall("call_1", "echo", "{}"))),
                )

            val messages =
                mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)["messages"]!!.jsonArray
            val assistant = messages.single().jsonObject

            assertThat(assistant["content"].toString()).isEqualTo("null")
            val toolCalls = assistant["tool_calls"]!!.jsonArray
            assertThat(
                toolCalls
                    .single()
                    .jsonObject["id"]!!
                    .jsonPrimitive.content,
            ).isEqualTo("call_1")
        }

    @Test
    fun `tools and tool_choice omitted when no tools`() =
        runTest {
            val request = requestOf(Msg(Role.USER, listOf(Block.Text("hi"))))

            val body = mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)

            assertThat(body.containsKey("tools")).isFalse()
            assertThat(body.containsKey("tool_choice")).isFalse()
            assertThat(body.containsKey("stream_options")).isFalse()
        }

    @Test
    fun `unavailable user image degrades to placeholder text part`() =
        runTest {
            val mapper = OpenAiRequestMapper(FakeImageLoader(broken = true), FakePrompts())
            val request =
                requestOf(
                    Msg(
                        Role.USER,
                        listOf(Block.Text("look"), Block.Image("gone.png", "image/png", 100, 80)),
                    ),
                )

            val messages =
                mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)["messages"]!!.jsonArray
            val parts = messages.single().jsonObject["content"]!!.jsonArray

            assertThat(parts).hasSize(2)
            assertThat(parts[1].jsonObject.keys).containsExactly("type", "text")
            assertThat(parts[1].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("（图片不可用）")
        }

    @Test
    fun `unavailable tool image degrades without note message`() =
        runTest {
            val mapper = OpenAiRequestMapper(FakeImageLoader(broken = true), FakePrompts())
            val request =
                requestOf(
                    Msg(
                        Role.TOOL,
                        listOf(Block.ToolResult("c1", listOf(Block.Image("a.png", "image/png", 10, 10)), false)),
                    ),
                )

            val messages =
                mapper.mapChatRequest(request, quirks(), model = "m", includeUsage = false)["messages"]!!.jsonArray

            assertThat(messages).hasSize(1)
            assertThat(messages[0].jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("（图片不可用）")
        }

    private fun requestOf(vararg messages: Msg): LlmRequest =
        LlmRequest(system = emptyList(), messages = messages.toList(), maxOutputTokens = 4096)

    private fun quirks(maxTokensField: String = "max_tokens"): OpenAiQuirks =
        OpenAiQuirks(
            chatPath = "/chat/completions",
            embeddingsPath = "/embeddings",
            maxTokensField = maxTokensField,
            reasoningField = "reasoning_content",
            echoReasoning = false,
            contextExceededPatterns = listOf("ctx"),
            imageMaxEdge = 1568,
        )

    private fun buildParameters(): JsonObject = parse("""{"type": "object"}""")

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject

    private companion object {
        const val FULL_EXPECTED =
            """
            {
              "model": "m",
              "messages": [
                {"role": "system", "content": "sys A\n\nsys B"},
                {"role": "user", "content": "hi"},
                {
                  "role": "assistant",
                  "content": "calling",
                  "tool_calls": [
                    {"id": "call_1", "type": "function", "function": {"name": "echo", "arguments": "{\"x\":1}"}}
                  ]
                },
                {"role": "tool", "tool_call_id": "call_1", "content": "result"}
              ],
              "tools": [
                {
                  "type": "function",
                  "function": {"name": "echo", "description": "Echo back", "parameters": {"type": "object"}}
                }
              ],
              "tool_choice": "required",
              "max_tokens": 4096,
              "temperature": 0.2,
              "stream": true,
              "stream_options": {"include_usage": true}
            }
            """
    }
}
