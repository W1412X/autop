/**
 * 工具阶段（docs/tech/04 §3.3/§3.9）：批量执行、预算记账、
 * 结果笔记与 ask_user 桥接；从 TurnLoop 拆出。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunState
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.replay.ReplayStepResult
import com.example.autop.agent.runtime.tools.RouterToolInvoker
import com.example.autop.core.common.Outcome
import com.example.autop.llm.Block

/** 工具执行阶段。 */
internal class ToolPhase(
    private val req: RunRequest,
    private val controller: RunController,
    private val parts: RunParts,
    private val guards: RunGuards,
    private val journal: LoopJournal,
) {
    /** 本 Run 的工具上下文（动态 schema 生成也用它）。 */
    internal val context: ToolContext get() = toolCtx

    private val toolCtx: ToolContext by lazy {
        RuntimeToolContext(
            req = req,
            threadStore = controller.toolStore,
            ask = ::askUser,
            progress = { text -> controller.emit(AgentEvent.ToolProgress(callId = "", text = text)) },
            modelVision = parts.modelVision,
            tools = RouterToolInvoker(parts.router),
        )
    }

    /** 执行工具批次并记录预算（docs/tech/04 §3.3）。 */
    suspend fun execute(calls: List<Block.ToolCall>): List<ToolOutput> {
        controller.setState(RunState.EXECUTING_TOOLS)
        val outputs =
            parts.router.execute(
                calls,
                toolCtx,
                emitResult = { event ->
                    controller.append(event)
                    guards.noteAdded(event.blocks)
                },
                emitInjected = { blocks -> journal.injectBlocks(blocks, UserMessageKind.INJECTED) },
            )
        parts.engines.budget.recordToolRound(outputs)
        return outputs
    }

    /** 回放一步（docs/tech/04 §3.12）：与普通调用同链路，只跳过 schema 校验。 */
    suspend fun executeReplayStep(
        callId: String,
        action: ReplayableAction,
    ): ReplayStepResult {
        controller.setState(RunState.EXECUTING_TOOLS)
        val result = parts.router.executeReplay(callId, action, toolCtx)
        parts.engines.budget.recordToolRound(listOf(result.output))
        return ReplayStepResult(result.output, result.durationMs)
    }

    /** ask_user：等待用户回答（docs/tech/04 §3.9）；无端口时按超时处理。 */
    suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> {
        controller.setState(RunState.WAITING_USER)
        val interaction = parts.engines.ports.interaction
        val answer = interaction?.ask(req.runId, question, options, timeoutMs) ?: noUserInteraction()
        controller.setState(RunState.EXECUTING_TOOLS)
        return answer
    }
}
