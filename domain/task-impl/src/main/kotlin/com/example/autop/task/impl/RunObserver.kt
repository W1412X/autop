/**
 * Run 观察与收口（docs/tech/07 §4.3）：跟踪句柄状态/事件，维护
 * [ActiveRunInfo] 与逐 Run 事件中继（供 `RunService.liveEvents`），并写终态。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunState
import com.example.autop.core.common.Clock
import com.example.autop.core.common.RunId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.flow.FlowProgressFeed
import com.example.autop.llm.Block
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Run 观察器（供 DI 装配，docs/tech/07 §4.3）。 */
public class RunObserver(
    private val runStore: RunStore,
    private val configProvider: ConfigProvider,
    private val clock: Clock,
    private val flowFeed: FlowProgressFeed,
) {
    private val handles = ConcurrentHashMap<RunId, RunHandle>()
    private val eventFlows = ConcurrentHashMap<RunId, MutableSharedFlow<AgentEvent>>()
    private val _active = MutableStateFlow<ActiveRunInfo?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前活跃 Run（悬浮球与对话页使用）；流程执行时附带根 Run 名下的流程进度（P1-2）。 */
    val active: StateFlow<ActiveRunInfo?> =
        combine(_active, flowFeed.active) { run, flows ->
            run?.copy(flow = flows[run.run.id.value])
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Run 启动：登记句柄并置初始活跃信息。 */
    fun begin(
        record: RunRecord,
        handle: RunHandle,
    ) {
        handles[record.id] = handle
        _active.value = ActiveRunInfo(record, RunState.STARTING, null, 0, emptyList())
    }

    fun handleOf(runId: RunId): RunHandle? = handles[runId]

    /** Run 记录观察（持久化状态，供 `RunService.observeRun`）。 */
    fun observeRun(runId: RunId): Flow<RunRecord> = runStore.observe(runId)

    /** 逐 Run 事件中继（replay 取配置 `agent.ui.eventReplay`）。 */
    fun eventsOf(runId: RunId): SharedFlow<AgentEvent> = relayOf(runId).asSharedFlow()

    private fun relayOf(runId: RunId): MutableSharedFlow<AgentEvent> =
        eventFlows.computeIfAbsent(runId) {
            MutableSharedFlow(replay = configProvider.current()[AgentConfig.UI].eventReplay)
        }

    /** 观察句柄状态与事件：更新 Run 记录与活跃信息，直到被取消或 Run 收口。 */
    suspend fun watch(
        runId: RunId,
        handle: RunHandle,
    ): Unit =
        coroutineScope {
            launch {
                handle.state.collect { state ->
                    _active.update { info -> if (info?.run?.id == runId) info.copy(state = state) else info }
                    val status = state.toRunStatus() ?: return@collect
                    // 只改 status 一列（N180）：get-then-update 旧快照会覆盖 ask_user 刚写的
                    // waitingQuestion/waitingOptions（卡片变空白）。
                    runStore.mutate(runId) { it.copy(status = status) }
                }
            }
            launch {
                // 记录快照跟随持久化（N179）：ask_user 的 WAITING_USER/waitingQuestion、
                // 暂停原因等只写 runStore，不跟随则对话页 AskCard 读旧快照永不显示。
                runStore.observe(runId).collect { record ->
                    _active.update { info -> if (info?.run?.id == runId) info.copy(run = record) else info }
                }
            }
            launch {
                handle.events.collect { event -> onEvent(runId, event) }
            }
        }

    /** Run 收口：写终态（summary/errorCode/endedAt）。 */
    suspend fun finish(
        runId: RunId,
        finished: RunFinished,
    ) {
        runStore.mutate(runId) { record ->
            record.copy(
                status = finished.status.toRunStatus(),
                endedAt = clock.nowMillis(),
                summary = finished.summary ?: record.summary,
                errorCode = finished.errorCode ?: record.errorCode,
                pauseReason = null,
                waitingQuestion = null,
            )
        }
    }

    /** 释放句柄与事件流，清空活跃信息（只清自己那轮，防重叠 Run 被误清）。 */
    fun clear(runId: RunId) {
        handles.remove(runId)
        eventFlows.remove(runId)
        _active.update { info -> if (info?.run?.id == runId) null else info }
    }

    private suspend fun onEvent(
        runId: RunId,
        event: AgentEvent,
    ) {
        relayOf(runId).emit(event)
        when (event) {
            is AgentEvent.Persisted ->
                when (val payload = event.event.payload) {
                    is AssistantMessage -> {
                        val calls = payload.blocks.filterIsInstance<Block.ToolCall>()
                        if (calls.isNotEmpty()) {
                            _active.update { info ->
                                val call = calls.last()
                                info?.copy(
                                    stepCount = info.stepCount + calls.size,
                                    currentStep = stepTextOf(call.name, call.argsJson),
                                    toolId = call.name,
                                    toolTarget = targetOf(call.name, call.argsJson),
                                )
                            }
                        }
                    }
                    is PlanUpdated -> _active.update { it?.copy(plan = payload.items) }
                    is RunPaused ->
                        runStore.mutate(runId) {
                            it.copy(status = RunStatus.PAUSED, pauseReason = payload.reason)
                        }
                    is RunResumed ->
                        runStore.mutate(runId) {
                            it.copy(status = RunStatus.RUNNING, pauseReason = null)
                        }
                    else -> Unit
                }
            else -> Unit
        }
    }
}
