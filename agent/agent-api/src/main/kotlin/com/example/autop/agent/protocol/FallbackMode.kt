/**
 * JSON 回退模式（docs/tech/04 §3.8）：模型不支持原生 tools 时用文本 JSON 表达工具调用；
 * 解析失败注入 `agent.fallback.parse_error` 重试（≤ `agent.fallback.parseRetries`），
 * 超限后当纯文本收尾。
 *
 * 与 [RequestBuilder] 一同放在 agent-api，流程创作会话复用同一套能力判定与解析，
 * 避免“普通对话走回退、创作会话却硬发 native tools”的分叉。
 */
package com.example.autop.agent.protocol

import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.ToolSchema

/** 一轮回复的解析走向。 */
public sealed interface ResolvedCalls {
    /**
     * 有工具调用（原生 ToolCall 块或解析出的 `fb_<turn>_<i>` 调用）。
     * [fromText] 为真表示调用是从正文 JSON 解析出来的（消息里没有 ToolCall 块）。
     */
    public data class ToolCalls(
        val calls: List<Block.ToolCall>,
        val fromText: Boolean = false,
    ) : ResolvedCalls

    /** 无工具调用的自然收尾；[text] 为回退协议的 `final` 文本（原生模式为 null，用模型原文）。 */
    public data class Done(
        val text: String?,
    ) : ResolvedCalls

    /** 解析失败，已生成重试提示（作为 INJECTED 注入后等下一轮）。 */
    public data class Retry(
        val injectText: String,
    ) : ResolvedCalls
}

/** 工具调用解析器（每次 Run 一个，携带解析重试计数）。 */
public class FallbackMode(
    private val prompts: PromptRepository,
    private val nativeTools: Boolean,
    private val parseRetriesLimit: Int,
) {
    private var parseRetries = 0

    /** 回退协议的 SYSTEM_STATIC 追加片段（原生模式为 null）。 */
    public fun staticToolList(schemas: List<ToolSchema>): String? {
        if (nativeTools) return null
        return prompts
            .render(ID_JSON_PROTOCOL, mapOf("toolList" to JsonToolProtocol.toolListText(schemas)))
            .getOrNull()
            ?.text
    }

    /**
     * 解析本轮回复的工具调用（docs/tech/04 §3.8）：两种模式都**先读外层 ToolCall 块，没有再读正文**，
     * 能力开关测得不准时调用也不会被漏掉。
     * - 原生模式：正文只在整段恰好是协议 JSON 且工具名都在 [toolNames] 中时当作调用
     *   （[JsonToolProtocol.parseStrictCalls]），否则是最终回答；
     * - 回退模式：按协议宽松解析正文（容忍前后说明文字），失败注入重试提示。
     */
    public fun resolveCalls(
        msg: Msg,
        turn: Int,
        toolNames: Set<String>,
    ): ResolvedCalls {
        val outer = msg.blocks.filterIsInstance<Block.ToolCall>()
        if (outer.isNotEmpty()) return ResolvedCalls.ToolCalls(outer)
        if (nativeTools) {
            val inText = JsonToolProtocol.parseStrictCalls(allTextOf(msg), turn, toolNames)
            return if (inText == null) ResolvedCalls.Done(null) else ResolvedCalls.ToolCalls(inText, fromText = true)
        }
        return when (val parsed = JsonToolProtocol.parse(lastTextOf(msg), turn)) {
            is JsonParsed.Calls -> ResolvedCalls.ToolCalls(parsed.calls, fromText = true)
            is JsonParsed.Final -> ResolvedCalls.Done(parsed.text)
            is JsonParsed.Invalid -> retryOrFail(parsed.error)
        }
    }

    /**
     * 写入历史的助手消息：调用从正文解析出来时，正文（协议 JSON）替换为 [Block.ToolCall] 块。
     * 工具结果要靠 ToolCall 块配对（缺块会被当成无主结果丢弃）；回退模式发送前
     * [JsonToolProtocol.downgrade] 再按协议格式还原成 JSON 正文。思考等非文本块原样保留。
     */
    public fun persistable(
        msg: Msg,
        resolved: ResolvedCalls,
    ): Msg {
        if (resolved !is ResolvedCalls.ToolCalls || !resolved.fromText) return msg
        return msg.copy(blocks = msg.blocks.filter { it !is Block.Text } + resolved.calls)
    }

    private fun allTextOf(msg: Msg): String = msg.blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }

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
