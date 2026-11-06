/**
 * AI 创建流程的对话历史修复（修复“user message before previous tool calls were answered”崩溃）：
 * 助手消息里的工具调用若没有对应的工具结果，后续的用户消息会被 LLM 校验拒绝。
 * 取消（停止按钮、协程被取消）发生在工具执行途中时，结果可能没有写入，这里为它们补上“已取消”的结果。
 */
package com.example.autop.flow.impl.author

import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/**
 * 若历史以“未得到结果的工具调用”结尾，补一条工具消息，结果内容为 [cancelledText]。
 * 返回是否做了补齐。只会出现在最后一条消息（之前的消息由正常循环保证配对）。
 */
internal fun MutableList<Msg>.repairDanglingToolCalls(cancelledText: String): Boolean {
    val last = lastOrNull() ?: return false
    if (last.role != Role.ASSISTANT) return false
    val calls = last.blocks.filterIsInstance<Block.ToolCall>()
    if (calls.isEmpty()) return false
    val results = calls.map { call -> Block.ToolResult(call.id, listOf(Block.Text(cancelledText)), isError = true) }
    add(Msg(Role.TOOL, results))
    return true
}
