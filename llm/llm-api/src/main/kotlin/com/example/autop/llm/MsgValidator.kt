/**
 * 消息序列约束检查（docs/tech/03 §1）：发送前调用，违反是编程错误，抛 [IllegalStateException]。
 */
package com.example.autop.llm

/**
 * 校验 [messages] 的结构约束：
 *
 * - [Block.ToolCall] 只能在 ASSISTANT 消息；[Block.ToolResult] 只能在 TOOL 消息；
 * - TOOL 消息只能包含 [Block.ToolResult]；
 * - 每个 `ToolCall.id` 在后续恰好有一个 `ToolResult.callId` 对应，且结果出现在
 *   下一条非 ASSISTANT 消息之前（中断时由 runtime 补 `isError=true` 结果）。
 */
public object MsgValidator {
    /** 校验失败抛 [IllegalStateException]（编程错误）。 */
    public fun validate(messages: List<Msg>) {
        var pending: Set<String>? = null
        for (msg in messages) {
            checkBlockRoles(msg)
            when (msg.role) {
                Role.ASSISTANT -> {
                    check(pending == null) { "assistant message before previous tool calls were answered" }
                    pending = toolCallIds(msg).takeIf { it.isNotEmpty() }
                }
                Role.TOOL -> {
                    val expected = checkNotNull(pending) { "tool message without preceding tool calls" }
                    checkToolOnly(msg)
                    val answered = toolResultIds(msg)
                    check(answered == expected) {
                        "tool results $answered do not match pending tool calls $expected"
                    }
                    pending = null
                }
                Role.USER -> {
                    check(pending == null) { "user message before previous tool calls were answered" }
                }
            }
        }
        check(pending == null) { "message list ends with unanswered tool calls $pending" }
    }

    private fun checkBlockRoles(msg: Msg) {
        for (block in msg.blocks) {
            check(block !is Block.ToolCall || msg.role == Role.ASSISTANT) {
                "tool_call block outside assistant message"
            }
            check(block !is Block.ToolResult || msg.role == Role.TOOL) {
                "tool_result block outside tool message"
            }
        }
    }

    private fun toolCallIds(msg: Msg): Set<String> {
        val ids = msg.blocks.filterIsInstance<Block.ToolCall>().map { it.id }
        check(ids.size == ids.toSet().size) { "duplicate tool call id in one message: $ids" }
        return ids.toSet()
    }

    private fun toolResultIds(msg: Msg): Set<String> {
        val ids = msg.blocks.map { (it as Block.ToolResult).callId }
        check(ids.size == ids.toSet().size) { "duplicate tool result call id in one message: $ids" }
        return ids.toSet()
    }

    private fun checkToolOnly(msg: Msg) {
        for (block in msg.blocks) {
            check(block is Block.ToolResult) { "tool message contains non-tool_result block: $block" }
        }
    }
}
