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

private const val XML_TOOL_MARK: String = "<tool_call>"
private const val XML_FUNCTION_MARK: String = "<function="
private const val FENCE_MARK: String = "```"

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
