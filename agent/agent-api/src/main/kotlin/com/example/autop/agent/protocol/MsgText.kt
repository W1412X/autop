/**
 * 消息文本提取：回退协议解析与各会话共用。
 */
package com.example.autop.agent.protocol

import com.example.autop.llm.Block
import com.example.autop.llm.Msg

/** 消息中最后一个 Text 块（模型最后的文本）。 */
public fun lastTextOf(msg: Msg): String =
    msg.blocks
        .filterIsInstance<Block.Text>()
        .lastOrNull()
        ?.text
        .orEmpty()

/** 工具调用标记被当正文时的识别提示（原生 tools 未被服务端解析）。 */
public const val TOOL_MARK_NOTE: String = "（检测到工具调用标记文本，未按工具调用解析）"

// 标记常量用拼接构造，避免源码里出现连续的调用标记字面量。
private const val XML_TOOL_MARK: String = "<" + "tool_call>"
private const val XML_FUNCTION_MARK: String = "<" + "function="
private const val XML_PARAMETER_MARK: String = "<" + "parameter="
private const val FENCE_MARK: String = "```"

/** 工具调用标记块（含闭合）整体匹配，跨行；用于收口总结净化。 */
private val TOOL_MARK_BLOCK =
    Regex(
        "<" + "tool_call>.*?</" + "tool_call>" +
            "|<" + "function=.*?</" + "function>" +
            "|<" + "parameter=.*?</" + "parameter>",
        RegexOption.DOT_MATCHES_ALL,
    )

/**
 * 助手正文的展示形态：整体是回退协议 JSON 时 `tool_calls` 不上屏、`final` 只展示正文；
 * 含工具调用 XML 标记时加识别提示（保留原文，不做转换）；其余原样。
 */
public fun displayTextOf(text: String): String =
    if (XML_TOOL_MARK in text || XML_FUNCTION_MARK in text) {
        "$TOOL_MARK_NOTE\n\n$text"
    } else {
        protocolBodyOf(text) ?: text
    }

/**
 * 收口总结的净化：finalRound 已剥掉工具，模型仍可能幻觉出工具调用标记或回退协议 JSON。
 * 剥离这些块后只保留可读正文；无正文返回 null（由调用方决定缺省展示），不把协议原文当总结。
 */
public fun finalSummaryOf(text: String): String? {
    val stripped = TOOL_MARK_BLOCK.replace(text, "")
    val withoutStrayMarks =
        stripped
            .lines()
            .filterNot { XML_TOOL_MARK in it || XML_FUNCTION_MARK in it || XML_PARAMETER_MARK in it }
            .joinToString("\n")
            .trim()
    val body = (protocolBodyOf(withoutStrayMarks) ?: withoutStrayMarks).trim()
    return body.ifBlank { null }
}

/** 整体是回退协议 JSON（容忍 ``` 包裹，同执行侧）时的展示正文；`tool_calls` 为 ""、非协议时 null。 */
private fun protocolBodyOf(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith('{') && !trimmed.startsWith(FENCE_MARK)) return null
    return when (val parsed = JsonToolProtocol.parse(trimmed, turn = 0)) {
        is JsonParsed.Final -> parsed.text
        is JsonParsed.Calls -> ""
        is JsonParsed.Invalid -> null
    }
}
