/**
 * 统一模型 → Anthropic messages 线格式（docs/tech/03 §5.2 请求表）：
 * system 数组、Thinking 原样回传、tool_result 进 user 消息、相邻同角色合并、
 * 首条必须是 user、缓存标记。只做格式转换，传输由 llm-http 处理。
 * 单图加载失败降级为占位文本（[IMAGE_UNAVAILABLE_NOTE]），不抬升为请求失败（N178）。
 */
package com.example.autop.llm.anthropic

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.ImageLoader
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** 模板 id（docs/tech/09 照抄）：首条非 user 时补的引导消息。 */
public const val LEADING_USER_ID: String = "llm.anthropic.leading_user"

/** 单图加载失败时替换进上下文的占位文本（两协议 mapper 同文，N178）。 */
private const val IMAGE_UNAVAILABLE_NOTE: String = "（图片不可用）"

public class AnthropicRequestMapper(
    private val images: ImageLoader,
    private val prompts: PromptRepository,
) {
    public suspend fun mapChatRequest(
        request: LlmRequest,
        quirks: AnthropicQuirks,
        model: String,
    ): JsonObject {
        val system =
            request.system.map { part ->
                var block =
                    buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    }
                if (part.cacheBreakpoint) block = withCacheControl(block)
                block
            }
        val messages = mapWireMessages(request, quirks)
        return buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxOutputTokens)
            if (system.isNotEmpty()) put("system", JsonArray(system))
            put("messages", JsonArray(messages))
            val omitTools =
                request.toolChoice == ToolChoice.NONE && quirks.toolChoiceNone == ToolChoiceNoneMode.OMIT_TOOLS
            if (request.tools.isNotEmpty() && !omitTools) {
                put("tools", mapTools(request.tools))
                put(
                    "tool_choice",
                    when (val toolChoice = request.toolChoice) {
                        ToolChoice.AUTO -> buildJsonObject { put("type", "auto") }
                        ToolChoice.NONE -> buildJsonObject { put("type", "none") }
                        ToolChoice.REQUIRED -> buildJsonObject { put("type", "any") }
                        is ToolChoice.Named ->
                            buildJsonObject {
                                put("type", "tool")
                                put("name", toolChoice.name)
                            }
                    },
                )
            }
            val budget = request.thinkingBudgetTokens
            if (budget != null) {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", budget)
                    },
                )
            } else {
                request.temperature?.let { put("temperature", it) }
            }
            put("stream", true)
        }
    }

    private suspend fun mapWireMessages(
        request: LlmRequest,
        quirks: AnthropicQuirks,
    ): List<JsonObject> {
        val flat = mutableListOf<WireMessage>()
        for ((index, msg) in request.messages.withIndex()) {
            val breakpoint = index == request.cacheBreakpointMessageIndex
            flat += wireMessage(msg, quirks, breakpoint)
        }
        val merged = mutableListOf<WireMessage>()
        for (message in flat) {
            val last = merged.lastOrNull()
            if (last != null && last.role == message.role) {
                merged[merged.lastIndex] = WireMessage(message.role, last.blocks + message.blocks)
            } else {
                merged += message
            }
        }
        return if (merged.firstOrNull()?.role == "user") {
            merged.map { it.toJson() }
        } else {
            listOf(leadingUser()) + merged.map { it.toJson() }
        }
    }

    private suspend fun wireMessage(
        msg: Msg,
        quirks: AnthropicQuirks,
        breakpoint: Boolean,
    ): WireMessage {
        val blocks =
            when (msg.role) {
                Role.USER -> msg.blocks.map { mapContentBlock(it, quirks) }
                Role.ASSISTANT -> msg.blocks.map { mapContentBlock(it, quirks) }
                Role.TOOL -> msg.blocks.filterIsInstance<Block.ToolResult>().map { toolResultBlock(it, quirks) }
            }
        val marked =
            if (breakpoint && blocks.isNotEmpty()) {
                blocks.dropLast(1) + withCacheControl(blocks.last())
            } else {
                blocks
            }
        val role = if (msg.role == Role.ASSISTANT) "assistant" else "user"
        return WireMessage(role, marked)
    }

    private suspend fun mapContentBlock(
        block: Block,
        quirks: AnthropicQuirks,
    ): JsonObject =
        when (block) {
            is Block.Text ->
                buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                }
            is Block.Image -> imageOrPlaceholder(block, quirks.imageMaxEdge)
            is Block.ToolCall ->
                buildJsonObject {
                    put("type", "tool_use")
                    put("id", block.id)
                    put("name", block.name)
                    put("input", parseArgs(block.argsJson))
                }
            is Block.Thinking ->
                if (block.redacted != null) {
                    buildJsonObject {
                        put("type", "redacted_thinking")
                        put("data", block.redacted)
                    }
                } else {
                    buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.text)
                        // 净化只保留带签名的思考块（WP6.7）。
                        put("signature", requireNotNull(block.signature) { "sanitizer keeps only signed thinking" })
                    }
                }
            is Block.ToolResult -> toolResultBlock(block, quirks)
        }

    private suspend fun toolResultBlock(
        result: Block.ToolResult,
        quirks: AnthropicQuirks,
    ): JsonObject =
        buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", result.callId)
            put("content", JsonArray(result.content.map { mapContentBlock(it, quirks) }))
            put("is_error", result.isError)
        }

    /** 单图加载：失败降级为占位文本块（N178），不再抬升为整个请求失败。 */
    private suspend fun imageOrPlaceholder(
        image: Block.Image,
        maxEdge: Int,
    ): JsonObject {
        val loaded = images.load(image.fileRef, image.mime, maxEdge)
        return if (loaded == null) {
            buildJsonObject {
                put("type", "text")
                put("text", IMAGE_UNAVAILABLE_NOTE)
            }
        } else {
            buildJsonObject {
                put("type", "image")
                put(
                    "source",
                    buildJsonObject {
                        put("type", "base64")
                        put("media_type", loaded.mime)
                        put("data", Base64.getEncoder().encodeToString(loaded.bytes))
                    },
                )
            }
        }
    }

    private fun withCacheControl(block: JsonObject): JsonObject =
        JsonObject(
            block +
                (
                    "cache_control" to
                        buildJsonObject { put("type", "ephemeral") }
                ),
        )

    private fun parseArgs(argsJson: String): JsonObject = Json.parseToJsonElement(argsJson).jsonObject

    private fun leadingUser(): JsonObject {
        val rendered = prompts.render(LEADING_USER_ID)
        val text =
            when (rendered) {
                is Outcome.Ok -> rendered.value.text
                is Outcome.Err -> throw LlmException(rendered.error)
            }
        return WireMessage(
            "user",
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            ),
        ).toJson()
    }

    private fun mapTools(tools: List<ToolSchema>): JsonArray =
        JsonArray(
            tools.map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters)
                }
            },
        )

    private class WireMessage(
        val role: String,
        val blocks: List<JsonObject>,
    ) {
        fun toJson(): JsonObject =
            buildJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
    }
}
