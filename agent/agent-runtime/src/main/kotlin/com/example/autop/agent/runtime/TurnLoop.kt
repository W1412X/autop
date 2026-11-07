/**
 * 主循环（docs/tech/04 §3.2）：组装上下文 → 采样 → 工具执行 → 守卫
 * （预算/熔断/压缩/计划门）→ 结束判定；取消时补齐未配对工具结果并写 RunFinished(CANCELLED)。
 * 采样（TurnSampler）、工具阶段（ToolPhase）、事件写入（LoopJournal）拆分为协作类。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.RenderItem
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.ResolvedCalls
import com.example.autop.agent.protocol.lastTextOf
import com.example.autop.agent.runtime.guard.BreakerVerdict
import com.example.autop.agent.runtime.replay.ReplayRunner
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptSource
import com.example.autop.llm.Block
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmStreamEvent
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
    private val sampler = TurnSampler(req, controller, guards, parts.engines.ports.prompts)
    private val phase = ToolPhase(req, controller, parts, guards, journal)
    private val allSchemas by lazy { parts.router.schemas(phase.context) }

    private var finalRound = false
    private var finalRoundByBudget = false
    private var budgetPromptInjected = false

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
        val context =
            ContextRequest(
                runId = req.runId,
                threadId = req.threadId,
                config = req.config,
                goal = req.goal,
                vars = req.vars,
                isFirstRunOfThread = controller.toolStore.events(req.threadId).none { it.payload is RunStarted },
            )
        val assembled = parts.assembler.assemble(context, req.config, sampler.staticToolList(allSchemas))
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
        while (true) {
            controller.awaitOpen()
            // N192：resume/steer 排队消息在每轮采样前落盘（上一批次 ToolResult 已落盘，
            // 序列合法且本轮可见；afterCalls 的 drain 仍在批次后兜底）。
            journal.drainSteers()
            val step = round(assembled.system, provider)
            if (step is LoopStep.End) return step.event
        }
    }

    private suspend fun round(
        sysBase: List<SystemPart>,
        provider: LlmProvider,
    ): LoopStep {
        if (parts.engines.budget.exhausted()) {
            finalRound = true
            finalRoundByBudget = true
        }
        var live = liveView()
        val sys = sysBase + listOfNotNull(parts.engines.notes.systemPart(req.threadId, req.config[AgentConfig.NOTES]))
        if (guards.overThreshold(sys, live, allSchemas)) {
            when (val compacted = guards.compact(sys)) {
                is CompactResult.End -> return fail(compacted.error)
                CompactResult.Continue -> live = liveView()
            }
        }
        if (finalRoundByBudget && !budgetPromptInjected) {
            budgetPromptInjected = true
            journal.inject(ID_FINAL_ROUND, emptyMap())
            live = liveView()
        }
        val schemas = if (finalRound) emptyList() else allSchemas
        val estimated = guards.estimate(sys, live.map { it.msg }, schemas)
        return when (val sampled = sampler.sample(provider, sys, schemas, finalRound) { liveView().map { it.msg } }) {
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
                afterSample(sys, completed)
            }
        }
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
    private suspend fun afterSample(
        sys: List<SystemPart>,
        completed: LlmStreamEvent.Completed,
    ): LoopStep =
        when (val resolved = sampler.resolveCalls(completed.message)) {
            is ResolvedCalls.Retry -> {
                journal.injectBlocks(listOf(Block.Text(resolved.injectText)), UserMessageKind.INJECTED)
                LoopStep.NextRound
            }
            else -> afterCalls(sys, completed, resolved)
        }

    /** 工具批次与收尾判定。 */
    private suspend fun afterCalls(
        sys: List<SystemPart>,
        completed: LlmStreamEvent.Completed,
        resolved: ResolvedCalls,
    ): LoopStep {
        val calls = (resolved as? ResolvedCalls.ToolCalls)?.calls.orEmpty()
        val finalText = (resolved as? ResolvedCalls.Done)?.text
        if (calls.isNotEmpty()) {
            val outputs = phase.execute(calls)
            val control = handleControl(outputs)
            if (control != null) return control
            applyBreaker(guards.breakerVerdict(calls, outputs))
        }
        val steers = journal.drainSteers()
        return if (calls.isEmpty() && steers.isEmpty()) {
            naturalEnd(finalText ?: lastTextOf(completed.message))
        } else {
            midTurnCompact(sys)
        }
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

    /** 自然结束判定（docs/tech/04 §3.2）：finalRound → BUDGET_EXHAUSTED；计划门拦一次；否则成功。 */
    private suspend fun naturalEnd(text: String): LoopStep {
        if (finalRound) return LoopStep.End(journal.finishEvent(RunOutcome.BUDGET_EXHAUSTED, text, null, null))
        val open = guards.planGateItems()
        return if (open != null) {
            journal.inject(ID_UNFINISHED, mapOf("items" to open.planText()))
            LoopStep.NextRound
        } else {
            LoopStep.End(journal.finishEvent(RunOutcome.SUCCEEDED, text, null, null))
        }
    }

    /** MID_TURN 压缩（docs/tech/04 §3.2 末行）。 */
    private suspend fun midTurnCompact(sys: List<SystemPart>): LoopStep {
        val live = liveView()
        if (!guards.overThreshold(sys, live, emptyList())) return LoopStep.NextRound
        val compacted = guards.compact(sys)
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
