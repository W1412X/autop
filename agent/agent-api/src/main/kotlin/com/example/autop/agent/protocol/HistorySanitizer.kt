/**
 * 发送前历史净化（agent-harness-overhaul D12）：任何已写入的历史都不能让之后的请求必然失败。
 * 只做删除/替换，不改变 ToolCall 与 ToolResult 的配对（WP6.7）。
 */
package com.example.autop.agent.protocol

import com.example.autop.core.common.getOrNull
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 净化目标：当前请求所用的模型配置。 */
public data class SanitizeTarget(
    val profileId: String,
    /** 是否回传思考块：Anthropic 协议且本次请求开启思考，或 OpenAI 协议且 quirk `echoReasoning=true`。 */
    val keepThinking: Boolean,
    /** Anthropic 协议要求思考块带签名/脱敏回传；OpenAI 回显不要求（WP6.7）。 */
    val requireThinkingSignature: Boolean = true,
)

/** 净化时插入的占位文字（来自模板，调用方渲染后传入）。 */
public data class SanitizeTexts(
    val emptyToolResult: String,
)

private const val ID_EMPTY_TOOL_RESULT = "agent.history.empty_tool_result"

/** 从模板渲染净化占位文字（`agent.history.empty_tool_result`）。 */
public fun sanitizeTextsOf(prompts: com.example.autop.core.prompt.PromptRepository): SanitizeTexts =
    SanitizeTexts(
        emptyToolResult =
            prompts
                .render(ID_EMPTY_TOOL_RESULT, emptyMap())
                .getOrNull()
                ?.text
                .orEmpty(),
    )

/** 发送前历史净化。 */
public object HistorySanitizer {
    /** 逐块净化；块全被删除的 USER/ASSISTANT 消息整条删除（TOOL 消息恒保留）。 */
    public fun sanitize(
        messages: List<Msg>,
        target: SanitizeTarget,
        texts: SanitizeTexts,
    ): List<Msg> =
        messages.mapNotNull { msg ->
            val blocks = msg.blocks.flatMap { blockOf(it, msg, target, texts) }
            if (blocks.isEmpty() && msg.role != Role.TOOL) null else msg.copy(blocks = blocks)
        }

    private fun blockOf(
        block: Block,
        msg: Msg,
        target: SanitizeTarget,
        texts: SanitizeTexts,
    ): List<Block> =
        when (block) {
            is Block.Text -> if (block.text.isBlank()) emptyList() else listOf(block)
            is Block.Thinking -> if (keepThinking(block, msg, target)) listOf(block) else emptyList()
            is Block.Image -> listOf(block)
            is Block.ToolCall -> listOf(validatedCall(block))
            is Block.ToolResult ->
                listOf(
                    block.copy(
                        content =
                            block.content
                                .flatMap { blockOf(it, msg, target, texts) }
                                .ifEmpty { listOf(Block.Text(texts.emptyToolResult)) },
                    ),
                )
        }

    private fun keepThinking(
        block: Block.Thinking,
        msg: Msg,
        target: SanitizeTarget,
    ): Boolean {
        if (!target.keepThinking || msg.producedBy != target.profileId) return false
        return !target.requireThinkingSignature || block.signature != null || block.redacted != null
    }

    /** 坏参数改写为合法 JSON 保留原文（`_invalid_arguments`）。 */
    private fun validatedCall(block: Block.ToolCall): Block {
        val ok =
            runCatching { Json.parseToJsonElement(block.argsJson) is JsonObject }.getOrDefault(false)
        if (ok) return block
        return block.copy(argsJson = buildJsonObject { put("_invalid_arguments", block.argsJson) }.toString())
    }
}
