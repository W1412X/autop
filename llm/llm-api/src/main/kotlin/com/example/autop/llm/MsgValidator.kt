/**
 * 消息序列约束检查（docs/tech/03 §1）：发送前调用 [problems] 收集全部违反项，
 * Provider 把非空结果转为 `llm.invalid_history`（agent-harness-overhaul WP6.5），
 * 不再把历史问题当编程异常抛出。
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
    /** 收集 [messages] 的全部违反项（英文描述），合法时为空；不抛异常。 */
    public fun problems(messages: List<Msg>): List<String> {
        val problems = mutableListOf<String>()
        var pending: Set<String>? = null
        for (msg in messages) {
            pending = messageProblems(msg, pending, problems)
        }
        if (pending != null) {
            problems += "message list ends with unanswered tool calls $pending"
        }
        return problems
    }

    /** 断言辅助（非发送路径的测试用）：有问题抛 [IllegalStateException]。 */
    public fun validate(messages: List<Msg>) {
        val problems = problems(messages)
        check(problems.isEmpty()) { problems.joinToString("; ") }
    }

    /** 单条消息的违反项与新的未决工具调用集合。 */
    private fun messageProblems(
        msg: Msg,
        pending: Set<String>?,
        problems: MutableList<String>,
    ): Set<String>? {
        problems += blockRoleProblems(msg)
        return when (msg.role) {
            Role.ASSISTANT -> {
                if (pending != null) {
                    problems += "assistant message before previous tool calls were answered"
                }
                callIds(msg, problems).takeIf { it.isNotEmpty() }
            }
            Role.TOOL -> {
                problems += toolOnlyProblems(msg)
                val answered = resultIds(msg, problems)
                when {
                    pending == null -> problems += "tool message without preceding tool calls"
                    answered != pending ->
                        problems += "tool results $answered do not match pending tool calls $pending"
                }
                null
            }
            Role.USER -> {
                if (pending != null) {
                    problems += "user message before previous tool calls were answered"
                }
                pending
            }
        }
    }

    private fun blockRoleProblems(msg: Msg): List<String> =
        buildList {
            for (block in msg.blocks) {
                if (block is Block.ToolCall && msg.role != Role.ASSISTANT) {
                    add("tool_call block outside assistant message")
                }
                if (block is Block.ToolResult && msg.role != Role.TOOL) {
                    add("tool_result block outside tool message")
                }
            }
        }

    private fun toolOnlyProblems(msg: Msg): List<String> =
        msg.blocks.mapNotNull { block ->
            if (block is Block.ToolResult) null else "tool message contains non-tool_result block: $block"
        }

    private fun callIds(
        msg: Msg,
        problems: MutableList<String>,
    ): Set<String> {
        val ids = msg.blocks.filterIsInstance<Block.ToolCall>().map { it.id }
        if (ids.size != ids.toSet().size) problems += "duplicate tool call id in one message: $ids"
        return ids.toSet()
    }

    private fun resultIds(
        msg: Msg,
        problems: MutableList<String>,
    ): Set<String> {
        val ids = msg.blocks.filterIsInstance<Block.ToolResult>().map { it.callId }
        if (ids.size != ids.toSet().size) problems += "duplicate tool result call id in one message: $ids"
        return ids.toSet()
    }
}
