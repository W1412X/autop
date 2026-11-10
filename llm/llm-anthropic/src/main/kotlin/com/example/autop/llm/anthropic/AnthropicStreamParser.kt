/**
 * Anthropic SSE 事件 → 统一流事件（docs/tech/03 §5.2 流解析表）。
 * Thinking 块原样回传（含 signature / redacted）；`error` 事件映射错误码抛 [LlmException]。
 */
package com.example.autop.llm.anthropic

import com.example.autop.core.common.AppError
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.example.autop.llm.http.mapHttpError
import com.example.autop.llm.http.matchesContextExceeded
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.TreeMap

public class AnthropicStreamParser(
    private val contextPatterns: List<String>,
) {
    private val blocks = TreeMap<Int, PendingBlock>()
    private var stopReason: StopReason? = null
    private var inputTokens = 0
    private var cacheReadTokens = 0
    private var cacheWriteTokens = 0
    private var outputTokens = 0
    private var stopped = false

    /** 投喂一个 SSE 事件，返回产生的事件；`error` 事件抛 [LlmException]。 */
    public fun feed(
        event: String?,
        data: JsonObject,
    ): List<LlmStreamEvent> =
        when (event) {
            "message_start" -> readInputUsage(data)
            "content_block_start" -> onBlockStart(data)
            "content_block_delta" -> onBlockDelta(data)
            "message_delta" -> onMessageDelta(data)
            "message_stop" -> onComplete()
            "error" -> throw mapError(data)
            else -> emptyList()
        }

    /** 流结束兜底：`message_stop` 已处理则为空，否则补发。 */
    public fun finishIfNeeded(): List<LlmStreamEvent> = if (stopped) emptyList() else onComplete()

    private fun readInputUsage(data: JsonObject): List<LlmStreamEvent> {
        // 显式 null 字段按缺失处理（兼容网关常见），下同
        val usage = (data["message"] as? JsonObject)?.get("usage") as? JsonObject
        inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        cacheReadTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        cacheWriteTokens = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        return emptyList()
    }

    private fun onBlockStart(data: JsonObject): List<LlmStreamEvent> {
        val index = data["index"]?.jsonPrimitive?.intOrNull
        val contentBlock = data["content_block"] as? JsonObject
        if (index == null || contentBlock == null) return emptyList()
        return when (contentBlock["type"]?.jsonPrimitive?.contentOrNull) {
            "tool_use" -> {
                val id = contentBlock["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = contentBlock["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                blocks[index] = PendingBlock.ToolCall(id, name)
                listOf(LlmStreamEvent.ToolCallStarted(index, id, name))
            }
            "thinking" -> {
                blocks[index] = PendingBlock.Thinking()
                emptyList()
            }
            "redacted_thinking" -> {
                blocks[index] = PendingBlock.Redacted(contentBlock["data"]?.jsonPrimitive?.contentOrNull.orEmpty())
                emptyList()
            }
            else -> {
                blocks[index] = PendingBlock.Text()
                emptyList()
            }
        }
    }

    private fun onBlockDelta(data: JsonObject): List<LlmStreamEvent> {
        val index = data["index"]?.jsonPrimitive?.intOrNull
        val delta = data["delta"] as? JsonObject
        val block = index?.let { blocks[it] }
        if (index == null || delta == null || block == null) return emptyList()
        return when (delta["type"]?.jsonPrimitive?.contentOrNull) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (block as? PendingBlock.Text)?.buffer?.append(text)
                listOf(LlmStreamEvent.TextDelta(text))
            }
            "thinking_delta" -> {
                val text = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (block as? PendingBlock.Thinking)?.text?.append(text)
                listOf(LlmStreamEvent.ThinkingDelta(text))
            }
            "signature_delta" -> {
                (block as? PendingBlock.Thinking)?.signature = delta["signature"]?.jsonPrimitive?.contentOrNull
                emptyList()
            }
            "input_json_delta" -> {
                val fragment = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (block as? PendingBlock.ToolCall)?.args?.append(fragment)
                if (fragment.isEmpty()) {
                    emptyList()
                } else {
                    listOf(LlmStreamEvent.ToolCallArgsDelta(index, fragment))
                }
            }
            else -> emptyList()
        }
    }

    private fun onMessageDelta(data: JsonObject): List<LlmStreamEvent> {
        val delta = data["delta"] as? JsonObject
        delta
            ?.get("stop_reason")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { stopReason = mapStopReason(it) }
        outputTokens = (data["usage"] as? JsonObject)
            ?.get("output_tokens")
            ?.jsonPrimitive
            ?.intOrNull ?: outputTokens
        return emptyList()
    }

    private fun onComplete(): List<LlmStreamEvent> {
        stopped = true
        val message = Msg(Role.ASSISTANT, blocks.values.map { it.toBlock() })
        // inputTokens 统一口径（WP4.4）：本次请求全部输入 = 基础输入 + 缓存命中 + 缓存写入。
        val currentUsage =
            Usage(
                inputTokens + cacheReadTokens + cacheWriteTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
            )
        val reason =
            stopReason ?: if (blocks.values.any { it is PendingBlock.ToolCall }) {
                StopReason.TOOL_USE
            } else {
                StopReason.END_TURN
            }
        return listOf(
            LlmStreamEvent.UsageReported(currentUsage),
            LlmStreamEvent.Completed(message, reason, currentUsage),
        )
    }

    private fun mapStopReason(raw: String): StopReason =
        when (raw) {
            "end_turn" -> StopReason.END_TURN
            "tool_use" -> StopReason.TOOL_USE
            "max_tokens" -> StopReason.MAX_TOKENS
            "stop_sequence" -> StopReason.STOP_SEQUENCE
            "refusal" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }

    private fun mapError(data: JsonObject): LlmException {
        val error = data["error"] as? JsonObject
        val type =
            error
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val message =
            error
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        return when (type) {
            "overloaded_error" -> LlmException(serverError(message))
            "rate_limit_error" ->
                LlmException(
                    AppError(code = LlmErrorCodes.RATE_LIMITED, message = message, retryable = true),
                )
            "invalid_request_error" ->
                if (matchesContextExceeded(message, contextPatterns)) {
                    LlmException(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = message))
                } else {
                    LlmException(AppError(code = LlmErrorCodes.BAD_REQUEST, message = message))
                }
            "authentication_error" -> LlmException(AppError(code = LlmErrorCodes.AUTH, message = message))
            else -> LlmException(AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "stream error: $message"))
        }
    }

    private fun serverError(message: String): AppError =
        mapHttpError(status = 529, bodySnippet = message, contextPatterns = contextPatterns)

    private sealed class PendingBlock {
        abstract fun toBlock(): Block

        class Text : PendingBlock() {
            val buffer: StringBuilder = StringBuilder()

            override fun toBlock(): Block = Block.Text(buffer.toString())
        }

        class ToolCall(
            val id: String,
            val name: String,
        ) : PendingBlock() {
            val args: StringBuilder = StringBuilder()

            override fun toBlock(): Block = Block.ToolCall(id, name, args.toString().ifEmpty { "{}" })
        }

        class Thinking : PendingBlock() {
            val text: StringBuilder = StringBuilder()
            var signature: String? = null

            override fun toBlock(): Block = Block.Thinking(text.toString(), signature)
        }

        class Redacted(
            val data: String,
        ) : PendingBlock() {
            override fun toBlock(): Block = Block.Thinking("", redacted = data)
        }
    }
}
