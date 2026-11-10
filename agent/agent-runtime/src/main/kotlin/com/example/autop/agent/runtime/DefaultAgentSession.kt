/**
 * AgentSession 实现（docs/tech/04 §2.6/§3.2/§3.11）：start 组装
 * Router/Engines/TurnLoop 并在 scope 内跑主循环；fork 把 atSeq 时刻的 live
 * 与笔记复制到新线程的 Baseline(FORK)。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentSession
import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.RenderItem
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.runtime.compaction.CompactionCoordinator
import com.example.autop.agent.runtime.guard.Budget
import com.example.autop.agent.runtime.guard.CircuitBreaker
import com.example.autop.agent.runtime.notes.RunNotes
import com.example.autop.agent.runtime.state.BudgetStateSource
import com.example.autop.agent.runtime.state.RuntimeStateTracker
import com.example.autop.agent.runtime.state.ToolVisibilityStateSource
import com.example.autop.agent.runtime.tools.RouterBuilder
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.llm.Block
import com.example.autop.llm.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 默认 AgentSession 实现。 */
internal class DefaultAgentSession(
    private val services: RuntimeServices,
    private val extensions: RuntimeExtensions,
    private val ports: RuntimePorts,
) : AgentSession {
    private val routerBuilder =
        RouterBuilder(
            tools = extensions.tools,
            enrichers = extensions.enrichers,
            llm = services.llm,
            prompts = services.prompts,
            clock = services.clock,
            policy = ports.policy,
            platform = ports.platform,
            audit = ports.audit,
            logger = ports.logger,
        )

    @Suppress("TooGenericExceptionCaught") // 主循环异常统一兜底转 RunFinished(FAILED)，不外传（N168）
    override fun start(
        request: RunRequest,
        scope: CoroutineScope,
    ): RunHandle {
        val controller =
            RunController(
                req = request,
                store = services.store,
                scope = scope,
                eventReplay = request.config[AgentConfig.UI].eventReplay,
                prompts = services.prompts,
            )
        val job =
            scope.launch {
                controller.complete(
                    try {
                        run(request, controller)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ports.logger.e(TAG, "run_crashed", "runId" to request.runId, error = e)
                        val crashed =
                            RunFinished(
                                status = RunOutcome.FAILED,
                                summary = null,
                                errorCode = CommonErrorCodes.UNKNOWN,
                                liveNote = services.prompts.runEndedNote(RunOutcome.FAILED),
                            )
                        // 崩溃也要让后续 Run 看到收口说明（WP8.2）：先落盘 RunFinished 再 complete。
                        services.store.append(request.threadId, request.runId, crashed)
                        crashed
                    },
                )
            }
        controller.attach(job)
        return controller
    }

    override suspend fun fork(
        threadId: ThreadId,
        atSeq: Long,
        title: String?,
    ): Outcome<ThreadId> {
        if (atSeq !in services.store.forkablePoints(threadId)) {
            return Outcome.Err(
                AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "seq $atSeq 不是完整回合的分叉点"),
            )
        }
        val events = services.store.events(threadId, 0, atSeq)
        val config = services.config.current()
        var items = HistoryRenderer(services.store, extensions.rules).renderEvents(fromBaseline(events), config)
        items = dropTrailingUnpaired(items)
        val notes = RunNotes(services.store, services.prompts, TokenEstimator(config[AgentConfig.TOKENS])::estimateText)
        // 笔记以一条 USER 消息追加在基线末尾（WP4.2）。
        val noteMsg = notes.blockMessage(notes.notesOf(events), config[AgentConfig.NOTES])
        // 标题缺省继承源线程（分叉是同一对话的延续，避免落回「未命名对话」）。
        val resolvedTitle = title ?: services.store.titleOf(threadId)
        val newId = services.store.createThread(resolvedTitle, threadId to atSeq)
        services.store.append(
            newId,
            null,
            ThreadCreated(title = resolvedTitle, parentThreadId = threadId, parentSeq = atSeq),
        )
        services.store.append(
            newId,
            null,
            Baseline(
                reason = BaselineReason.FORK,
                messages = items.map { it.msg } + listOfNotNull(noteMsg),
                notes = notes.notesOf(events),
            ),
        )
        return Outcome.Ok(newId)
    }

    private suspend fun run(
        request: RunRequest,
        controller: RunController,
    ): RunFinished {
        val estimator = TokenEstimator(request.config[AgentConfig.TOKENS])
        val notes = RunNotes(controller.toolStore, services.prompts, estimator::estimateText)
        val modelVision = routerBuilder.modelVisionOf(request)
        val routerBuild = routerBuilder.build(request, request.toolFilter, modelVision)
        val budget = Budget(request.config[AgentConfig.BUDGET], services.clock)
        val state =
            RuntimeStateTracker(
                sources =
                    extensions.stateSources.associateBy { it.id } +
                        mapOf(
                            BudgetStateSource.ID to BudgetStateSource(budget, services.prompts),
                            ToolVisibilityStateSource.ID to
                                ToolVisibilityStateSource(routerBuild.hidden, services.prompts),
                        ),
                store = controller.toolStore,
                prompts = services.prompts,
                logger = ports.logger,
            )
        val engines =
            RunEngines(
                budget = budget,
                breaker = CircuitBreaker(request.config[AgentConfig.BREAKER]),
                compaction = CompactionCoordinator(controller.toolStore, extensions.compactors, request.runId),
                notes = notes,
                state = state,
                ports =
                    RunPorts(
                        services.llm,
                        services.prompts,
                        ports.logger,
                        ports.interaction,
                        ports.replayProbe,
                        ports.replayTargets,
                    ),
            )
        val loop =
            TurnLoop(
                req = request,
                controller = controller,
                parts =
                    RunParts(
                        renderer = HistoryRenderer(controller.toolStore, extensions.rules),
                        assembler = ContextAssembler(extensions.contributors),
                        router = routerBuild.router,
                        engines = engines,
                        modelVision = modelVision,
                    ),
            )
        return loop.run()
    }

    /** 从 atSeq 前最近的 Baseline 起的事件（无 Baseline 则全部，docs/tech/04 §3.11 第 1 步）。 */
    private fun fromBaseline(events: List<ThreadEvent>): List<ThreadEvent> {
        val idx = events.indexOfLast { it.payload is Baseline }
        return if (idx < 0) events else events.drop(idx)
    }

    /** fork 点必须是完整回合：末尾带未配对 ToolCall 的 ASSISTANT 截掉（docs/tech/04 §3.11 第 2 步）。 */
    private fun dropTrailingUnpaired(items: List<RenderItem>): List<RenderItem> {
        val last = items.lastOrNull() ?: return items
        val unpaired = last.msg.role == Role.ASSISTANT && last.msg.blocks.any { it is Block.ToolCall }
        return if (unpaired) items.dropLast(1) else items
    }

    private companion object {
        const val TAG = "AgentSession"
    }
}
