/**
 * 单轮输出异常分流（agent-harness-overhaul WP5.4）：拒答失败收口、MAX_TOKENS 截断重试、
 * 空回复重试；超过 `agent.turn` 上限时失败。计数器每 Run 一个。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.runtime.LoopJournal
import com.example.autop.agent.runtime.tools.ToolErrors
import com.example.autop.core.common.AppError
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.StopReason

/** 单轮走向。 */
internal sealed interface TurnVerdict {
    data object Proceed : TurnVerdict

    /** 重试本轮：[apply] 先配对截断的工具调用并注入提示。 */
    data class Retry(
        val apply: suspend () -> Unit,
    ) : TurnVerdict

    data class Fail(
        val error: AppError,
    ) : TurnVerdict
}

/** 单轮守卫（每次 Run 一个）。 */
internal class TurnGuard(
    private val req: RunRequest,
    private val journal: LoopJournal,
    private val errors: ToolErrors,
    private val prompts: PromptRepository,
) {
    private var truncated = 0
    private var empty = 0

    fun judge(
        completed: LlmStreamEvent.Completed,
        nativeTools: Boolean,
    ): TurnVerdict {
        if (completed.stopReason == StopReason.REFUSAL) {
            return TurnVerdict.Fail(AppError(code = AgentErrorCodes.MODEL_REFUSED, message = "model refused"))
        }
        if (completed.stopReason == StopReason.MAX_TOKENS) {
            truncated += 1
            val limit = req.config[AgentConfig.TURN].maxTruncatedRetries
            return if (truncated > limit) {
                TurnVerdict.Fail(AppError(code = AgentErrorCodes.OUTPUT_TRUNCATED, message = "output truncated"))
            } else {
                TurnVerdict.Retry {
                    pairTruncatedCalls(completed)
                    journal.inject(ID_OUTPUT_TRUNCATED, emptyMap())
                }
            }
        }
        // 回退协议的解析重试负责空回复（FallbackMode），这里只管原生工具模式。
        if (nativeTools && isEmptyReply(completed.message)) {
            empty += 1
            val limit = req.config[AgentConfig.TURN].maxEmptyRetries
            return if (empty > limit) {
                TurnVerdict.Fail(AppError(code = AgentErrorCodes.EMPTY_REPLY, message = "empty reply"))
            } else {
                TurnVerdict.Retry { journal.inject(ID_EMPTY_REPLY, emptyMap()) }
            }
        }
        truncated = 0
        empty = 0
        return TurnVerdict.Proceed
    }

    /** 截断输出里的 ToolCall 无法执行：补错误 ToolResult 保持配对。 */
    private suspend fun pairTruncatedCalls(completed: LlmStreamEvent.Completed) {
        val output =
            errors.errorOutput(
                AgentErrorCodes.OUTPUT_TRUNCATED,
                "output truncated before arguments completed",
                hints(),
            )
        for (block in completed.message.blocks) {
            if (block !is Block.ToolCall) continue
            journal.toolResult(
                ToolResultEvent(
                    callId = block.id,
                    toolId = block.name,
                    ok = false,
                    blocks = output.content,
                    errorCode = AgentErrorCodes.OUTPUT_TRUNCATED,
                    durationMs = 0L,
                ),
            )
        }
    }

    private fun isEmptyReply(message: com.example.autop.llm.Msg): Boolean {
        val hasCall = message.blocks.any { it is Block.ToolCall }
        if (hasCall) return false
        val text =
            message.blocks
                .filterIsInstance<Block.Text>()
                .joinToString("") { it.text }
                .trim()
        return text.isEmpty()
    }

    private fun hints(): String =
        prompts
            .render(ID_TRUNCATED_HINT, emptyMap())
            .getOrNull()
            ?.text
            .orEmpty()

    private companion object {
        const val ID_OUTPUT_TRUNCATED = "agent.turn.output_truncated"
        const val ID_EMPTY_REPLY = "agent.turn.empty_reply"
        const val ID_TRUNCATED_HINT = "agent.turn.truncated_call_hint"
    }
}
