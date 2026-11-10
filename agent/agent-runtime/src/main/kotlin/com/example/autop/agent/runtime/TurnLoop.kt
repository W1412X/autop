/**
 * 主循环（docs/tech/04 §3.2）：组装上下文 → 采样 → 工具执行 → 守卫
 * （预算/熔断/压缩/计划门）→ 结束判定；取消时补齐未配对工具结果并写 RunFinished(CANCELLED)。
 * 采样（TurnSampler）、工具阶段（ToolPhase）、事件写入（LoopJournal）拆分为协作类。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.RenderItem
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunStarted
import com.example.autop.agent.StateRequest
import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.ResolvedCalls
import com.example.autop.agent.protocol.finalSummaryOf
import com.example.autop.agent.protocol.lastTextOf
import com.example.autop.agent.runtime.guard.BreakerVerdict
import com.example.autop.agent.runtime.replay.ReplayRunner
import com.example.autop.agent.runtime.state.RuntimeStateTracker
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptSource
import com.example.autop.llm.Block
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.Msg
import com.example.autop.llm.SystemPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 一轮的走向。 */
private sealed interface LoopStep {
    /** Run 结束。 */
    data class End(
        val event: RunFinished,
    ) : LoopStep

    /** 进入下一轮。 */
    data object NextRound : LoopStep
}

/** 主循环（每次 Run 一个）。 */
@Suppress("TooManyFunctions") // 主循环各阶段（守卫/采样/工具/日志/回放）拆分的自然结果
internal class TurnLoop(
    private val req: RunRequest,
    private val controller: RunController,
    private val parts: RunParts,
) {
    private val prompts get() = parts.engines.ports.prompts
    private val guards = RunGuards(req, controller, parts.renderer, parts.engines, parts.router)
    private val journal = LoopJournal(controller, parts.engines.ports.prompts)
    private val sampler = TurnSampler(req, controller, parts.engines.ports.prompts)
    private val phase = ToolPhase(req, controller, parts, guards, journal)
    private val allSchemas by lazy { parts.router.schemas(phase.context) }

    private lateinit var contextRequest: ContextRequest
    private var assembled: AssembledContext? = null

    /** 当前 system（Run 内可变；压缩成功后重新组装，WP4.1）。 */
    private val system: List<SystemPart>
        get() = requireNotNull(assembled) { "context assembled in loop()" }.system

    private val stateRequest = StateRequest(req.runId, req.threadId, req.config, req.vars)

    private var finalRound = false
    private var finalRoundByBudget = false
    private var budgetPromptInjected = false
    private var nativeTools = true

    /**
     * 运行入口。取消时不重新抛出（句柄的 await/complete 仍要收口），
     * 在 NonCancellable 中补齐未配对工具结果并写 RunFinished(CANCELLED)。
     */
    suspend fun run(): RunFinished =
        try {
            loop()
        } catch (e: CancellationException) {
            parts.engines.ports.logger
                .d(TAG, "run cancelled", "runId" to req.runId, "reason" to e.message)
            withContext(NonCancellable) { cancelFinish() }
        }

    /** 失败收口（N168）：先记错误详情（code/message，可选堆栈）再写 RunFinished(FAILED)。 */
    private suspend fun fail(
        error: AppError,
        cause: Throwable? = null,
    ): LoopStep.End {
        parts.engines.ports.logger
            .w(
                TAG,
                "run_failed",
                "runId" to req.runId,
                "code" to error.code,
                "detail" to error.message,
                error = cause ?: error.cause,
            )
        return LoopStep.End(journal.finishEvent(RunOutcome.FAILED, null, null, error.code))
    }

    private suspend fun loop(): RunFinished {
        val provider =
            when (
                val resolved =
                    parts.engines.ports.llm
                        .forRole(req.modelRole)
            ) {
                is Outcome.Ok -> resolved.value
                is Outcome.Err -> return fail(resolved.error).event
            }
        guards.bindProfile(provider.profile)
        sampler.bindProfile(provider)
        nativeTools = provider.profile.capabilities.tools
        contextRequest =
            ContextRequest(
                runId = req.runId,
                threadId = req.threadId,
                config = req.config,
                goal = req.goal,
                vars = req.vars,
                isFirstRunOfThread = controller.toolStore.events(req.threadId).none { it.payload is RunStarted },
            )
        val assembled = parts.assembler.assemble(contextRequest, req.config, sampler.staticToolList(allSchemas))
        this.assembled = assembled
        controller.append(
            RunStarted(
                runId = req.runId,
                configDiff = req.config.diffFromDefaults(),
                promptVersions = assembled.promptIds,
                userPromptIds =
                    assembled.promptIds.keys
                        .filter { prompts.get(it).source == PromptSource.USER }
                        .toSet(),
                modelProfileId = provider.profile.id,
                toolIds = parts.router.toolIds(),
            ),
        )
        // FIRST_USER 系统上下文（环境/触发/goal 等）单独成 INJECTED：进模型但不进 UI
        // 时间线与记忆；用户输入单独成 INPUT（N174：此前拼成一条 INPUT，电量等系统提示
        // 显示成用户气泡）；无输入的 Run 不产生 INPUT 气泡。
        assembled.firstUser?.let {
            controller.append(UserMessage(blocks = listOf(Block.Text(it)), kind = UserMessageKind.INJECTED))
        }
        if (req.input.isNotEmpty()) {
            controller.append(UserMessage(blocks = req.input, kind = UserMessageKind.INPUT))
        }
        req.replay?.let { runReplayPrefix(it) }
        // 运行状态 tracker 放进 runScope 供 get_device_state 使用（WP3.8）。
        phase.context.runScope.getOrPut(RuntimeStateTracker.SCOPE_KEY) { parts.engines.state }
        while (true) {
            controller.awaitOpen()
            // N192：resume/steer 排队消息在每轮采样前落盘（上一批次 ToolResult 已落盘，
            // 序列合法且本轮可见；afterCalls 的 drain 仍在批次后兜底）。
            journal.drainSteers()
            // 运行状态只在变化时以 USER 消息追加；首轮自然是全量（WP3.4）。
            parts.engines.state.reportIfChanged(stateRequest)
            val step = round(provider)
            if (step is LoopStep.End) return step.event
        }
    }

    private suspend fun round(provider: LlmProvider): LoopStep {
        if (parts.engines.budget.exhausted()) {
            finalRound = true
            finalRoundByBudget = true
        }
        var live = liveView()
        if (guards.overThreshold(system, live, allSchemas)) {
            when (val compacted = compactNow()) {
                is CompactResult.End -> return fail(compacted.error)
                CompactResult.Continue -> live = liveView()
            }
        }
        if (finalRoundByBudget && !budgetPromptInjected) {
            budgetPromptInjected = true
            journal.inject(ID_FINAL_ROUND, mapOf(VAR_DIMENSION to budgetDimensionText()))
            live = liveView()
        }
        val schemas = if (finalRound) emptyList() else allSchemas
        val estimated = guards.estimate(system, live.map { it.msg }, schemas)
        return when (
            val sampled =
                sampler.sample(
                    provider,
                    schemas,
                    finalRound,
                    system = { system },
                    render = { liveView().map { it.msg } },
                    compact = ::compactNow,
                )
        ) {
            is Sampled.Failed -> fail(sampled.error, sampled.cause)
            is Sampled.Ok -> {
                val completed = sampled.completed
                parts.engines.budget.recordUsage(completed.usage, estimated)
                guards.noteReportedInput(completed.usage)
                controller.append(
                    AssistantMessage(
                        blocks = completed.message.blocks,
                        usage = completed.usage,
                        stopReason = completed.stopReason,
                        modelProfileId = provider.profile.id,
                    ),
                )
                guards.noteAdded(completed.message.blocks)
                afterSample(completed)
            }
        }
    }

    /** 压缩一次；成功后重新组装 system（记忆索引、流程目录等可能已变化，WP4.1）。 */
    private suspend fun compactNow(): CompactResult {
        val result = guards.compact(system, initialContext = requireNotNull(assembled).firstUser)
        if (result is CompactResult.Continue && guards.lastCompactionSucceeded) {
            assembled = parts.assembler.assemble(contextRequest, req.config, sampler.staticToolList(allSchemas))
        }
        return result
    }

    /** 预算触顶维度的展示文字（`agent.budget.dimension.<dim>`，WP4.6）。 */
    private fun budgetDimensionText(): String {
        val dim =
            parts.engines.budget
                .reason()
                .orEmpty()
        return prompts
            .render(ID_BUDGET_DIMENSION_PREFIX + dim, emptyMap())
            .getOrNull()
            ?.text
            .orEmpty()
    }

    /**
     * 回放前缀（docs/tech/04 §3.12）：逐步回放后把结果注入主循环——
     * 全部完成发 `agent.replay.completed`，对不上发 `agent.replay.diverged`（含剩余步骤）。
     */
    private suspend fun runReplayPrefix(plan: ReplayPlan) {
        val probe = parts.engines.ports.replayProbe ?: return
        val runner =
            ReplayRunner(
                plan = plan,
                probe = probe,
                config = req.config,
                resolver = parts.engines.ports.replayTargets,
                toolNameOf = parts.router::toolNameOf,
                emit = { payload -> controller.append(payload) },
                executeStep = phase::executeReplayStep,
            )
        val outcome = runner.run()
        val diverged = outcome.diverged
        if (diverged == null) {
            journal.inject(ID_REPLAY_COMPLETED, mapOf(VAR_STEPS to outcome.completedSteps.toString()))
        } else {
            journal.inject(
                ID_REPLAY_DIVERGED,
                mapOf(
                    VAR_INDEX to diverged.stepIndex.toString(),
                    VAR_REASON to diverged.reason,
                    VAR_REMAINING to ReplayRunner.remainingText(plan, diverged.stepIndex),
                ),
            )
        }
    }

    /** 采样后的走向：工具执行 → 控制 → 熔断 → steer → 自然结束判定。 */
    private suspend fun afterSample(completed: LlmStreamEvent.Completed): LoopStep =
        when (val resolved = sampler.resolveCalls(completed.message)) {
            is ResolvedCalls.Retry -> {
                journal.injectBlocks(listOf(Block.Text(resolved.injectText)), UserMessageKind.INJECTED)
                LoopStep.NextRound
            }
            else -> afterCalls(completed, resolved)
        }

    /** 工具批次与收尾判定。 */
    private suspend fun afterCalls(
        completed: LlmStreamEvent.Completed,
        resolved: ResolvedCalls,
    ): LoopStep {
        val calls = (resolved as? ResolvedCalls.ToolCalls)?.calls.orEmpty()
        val finalText = (resolved as? ResolvedCalls.Done)?.text
        if (calls.isNotEmpty()) {
            // 调用发出即播报（回退协议解析出的调用不落 ToolCall 块，UI 靠这条实时出行）。
            calls.forEachIndexed { index, call ->
                controller.emit(AgentEvent.ToolCallStreaming(index, call.name, callId = call.id))
            }
            val outputs = phase.execute(calls)
            val control = handleControl(outputs)
            if (control != null) return control
            applyBreaker(guards.breakerVerdict(calls, outputs))
        }
        val steers = journal.drainSteers()
        return if (calls.isEmpty() && steers.isEmpty()) {
            naturalEndOf(finalText, completed.message)
        } else {
            midTurnCompact()
        }
    }

    /**
     * 自然结束的最终文本：回退协议以 `final` 为准；解析重试超限（无 final）显式报错收口，
     * 不把协议原文当回复（同流程创作侧 LLM_INVALID_OUTPUT 口径）。
     */
    private suspend fun naturalEndOf(
        finalText: String?,
        message: Msg,
    ): LoopStep =
        if (finalText == null && !nativeTools) {
            fail(AppError(code = AgentErrorCodes.FALLBACK_PARSE_FAILED, message = "回退协议解析重试超限，仍无可用正文"))
        } else {
            naturalEnd(finalText ?: lastTextOf(message))
        }

    /** 控制工具：Finish 与 steer 竞争（R1/B2）；Pause 关门等 resume 后继续守卫流程。 */
    private suspend fun handleControl(outputs: List<ToolOutput>): LoopStep? {
        val control = outputs.firstNotNullOfOrNull { it.control } ?: return null
        return when (control) {
            is ToolControl.Pause -> {
                controller.pause(control.reason, control.detail)
                null
            }
            is ToolControl.Finish -> {
                val steers = journal.drainSteers()
                if (steers.isEmpty()) {
                    LoopStep.End(journal.finishEvent(control.status, control.summary, control.outputs, null))
                } else {
                    journal.inject(ID_DEFERRED, emptyMap())
                    LoopStep.NextRound
                }
            }
        }
    }

    /** 熔断判定注入（docs/tech/04 §3.7）：Stop 额外进入 finalRound。 */
    private suspend fun applyBreaker(verdict: BreakerVerdict) {
        when (verdict) {
            BreakerVerdict.None -> Unit
            is BreakerVerdict.Warn -> journal.inject(verdict.promptId, verdict.vars)
            is BreakerVerdict.Stop -> {
                journal.inject(verdict.promptId, verdict.vars)
                finalRound = true
            }
        }
    }

    /**
     * 自然结束判定（docs/tech/04 §3.2）：预算触顶 → BUDGET_EXHAUSTED；熔断 Stop → BREAKER_STOPPED
     * （两者都用 finalRound 收口，但状态/文案必须区分，N170 审计）；计划门拦一次；否则成功。
     * 总结文本经 [finalSummaryOf] 净化，避免 finalRound 幻觉出的工具调用原文成为 summary。
     */
    private suspend fun naturalEnd(text: String): LoopStep {
        if (finalRound) {
            val summary = finalSummaryOf(text)
            return if (finalRoundByBudget) {
                LoopStep.End(journal.finishEvent(RunOutcome.BUDGET_EXHAUSTED, summary, null, null))
            } else {
                parts.engines.ports.logger
                    .i(
                        TAG,
                        "run_breaker_stopped",
                        "runId" to req.runId,
                    )
                LoopStep.End(
                    journal.finishEvent(RunOutcome.BREAKER_STOPPED, summary, null, AgentErrorCodes.BREAKER_STOPPED),
                )
            }
        }
        val open = guards.planGateItems()
        return if (open != null) {
            journal.inject(ID_UNFINISHED, mapOf("items" to open.planText()))
            LoopStep.NextRound
        } else {
            LoopStep.End(journal.finishEvent(RunOutcome.SUCCEEDED, text, null, null))
        }
    }

    /** MID_TURN 压缩（docs/tech/04 §3.2 末行）；阈值估算带下一轮实际要发的工具（WP4.1）。 */
    private suspend fun midTurnCompact(): LoopStep {
        val live = liveView()
        val schemas = if (finalRound) emptyList() else allSchemas
        if (!guards.overThreshold(system, live, schemas)) return LoopStep.NextRound
        val compacted = compactNow()
        return if (compacted is CompactResult.End) {
            fail(compacted.error)
        } else {
            LoopStep.NextRound
        }
    }

    /** 取消收口（docs/tech/04 §3.2）：补齐未配对工具结果后写 RunFinished(CANCELLED)。 */
    private suspend fun cancelFinish(): RunFinished {
        controller.pairCancelledCalls(
            cancelledText =
                prompts
                    .render(ID_CANCELLED, emptyMap())
                    .getOrNull()
                    ?.text
                    .orEmpty(),
            toolIdOf = { name -> parts.router.toolFor(name)?.id ?: name },
        )
        return journal.finishEvent(RunOutcome.CANCELLED, null, null, null)
    }

    /** 实时上下文视图（lambda 属性，供 sample 闭包与各阶段取最新 live）。 */
    private val liveView: suspend () -> List<RenderItem> = { parts.renderer.live(req.threadId, req.config) }

    private companion object {
        const val TAG = "TurnLoop"
        const val ID_FINAL_ROUND = "agent.budget.final_round"
        const val ID_BUDGET_DIMENSION_PREFIX = "agent.budget.dimension."
        const val VAR_DIMENSION = "dimension"
        const val ID_UNFINISHED = "agent.plan.unfinished_items"
        const val ID_DEFERRED = "agent.finish.deferred_by_steer"
        const val ID_CANCELLED = "agent.tool.cancelled_result"
        const val ID_REPLAY_COMPLETED = "agent.replay.completed"
        const val ID_REPLAY_DIVERGED = "agent.replay.diverged"
        const val VAR_STEPS = "steps"
        const val VAR_INDEX = "index"
        const val VAR_REASON = "reason"
        const val VAR_REMAINING = "remaining"
    }
}
