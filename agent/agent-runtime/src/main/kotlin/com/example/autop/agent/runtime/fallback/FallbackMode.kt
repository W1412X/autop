/**
 * JSON 回退模式（docs/tech/04 §3.8）：模型不支持原生 tools 时用文本 JSON 表达工具调用；
 * 解析失败注入 `agent.fallback.parse_error` 重试（≤ `agent.fallback.parseRetries`），
 * 超限后当纯文本收尾。
 */
package com.example.autop.agent.runtime.fallback

import com.example.autop.agent.runtime.lastTextOf
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.ToolSchema

/** 一轮回复的解析走向。 */
internal sealed interface ResolvedCalls {
    /** 有工具调用（原生 ToolCall 块或解析出的 `fb_<turn>_<i>` 调用）。 */
    data class ToolCalls(
        val calls: List<Block.ToolCall>,
    ) : ResolvedCalls

    /** 无工具调用的自然收尾；[text] 为回退协议的 `final` 文本（原生模式为 null，用模型原文）。 */
    data class Done(
        val text: String?,
    ) : ResolvedCalls

    /** 解析失败，已生成重试提示（作为 INJECTED 注入后等下一轮）。 */
    data class Retry(
        val injectText: String,
    ) : ResolvedCalls
}

/** 工具调用解析器（每次 Run 一个，携带解析重试计数）。 */
internal class FallbackMode(
    private val prompts: PromptRepository,
    private val nativeTools: Boolean,
    private val parseRetriesLimit: Int,
) {
    private var parseRetries = 0

    /** 回退协议的 SYSTEM_STATIC 追加片段（原生模式为 null）。 */
    fun staticToolList(schemas: List<ToolSchema>): String? {
        if (nativeTools) return null
        return prompts
            .render(ID_JSON_PROTOCOL, mapOf("toolList" to JsonToolProtocol.toolListText(schemas)))
            .getOrNull()
            ?.text
    }

    /** 解析本轮回复的工具调用（docs/tech/04 §3.8）。 */
    fun resolveCalls(
        msg: Msg,
        turn: Int,
    ): ResolvedCalls {
        if (nativeTools) {
            val calls = msg.blocks.filterIsInstance<Block.ToolCall>()
            return if (calls.isEmpty()) ResolvedCalls.Done(null) else ResolvedCalls.ToolCalls(calls)
        }
        return when (val parsed = JsonToolProtocol.parse(lastTextOf(msg), turn)) {
            is JsonParsed.Calls -> ResolvedCalls.ToolCalls(parsed.calls)
            is JsonParsed.Final -> ResolvedCalls.Done(parsed.text)
            is JsonParsed.Invalid -> retryOrFail(parsed.error)
        }
    }

    /** 解析失败：限内注入 parse_error 重试；超限后当纯文本收尾。 */
    private fun retryOrFail(error: String): ResolvedCalls {
        if (parseRetries >= parseRetriesLimit) return ResolvedCalls.Done(null)
        parseRetries++
        val text =
            prompts
                .render(ID_PARSE_ERROR, mapOf("error" to error))
                .getOrNull()
                ?.text
                .orEmpty()
        return ResolvedCalls.Retry(text)
    }

    private companion object {
        const val ID_JSON_PROTOCOL = "agent.fallback.json_protocol"
        const val ID_PARSE_ERROR = "agent.fallback.parse_error"
    }
}
