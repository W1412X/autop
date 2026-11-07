package com.example.autop.flow.impl.run

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.EventPayload
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunState
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolControl
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.flow.FlowDebugControl
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowProgress
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.PauseResolution
import com.example.autop.flow.core.ControlPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 流程运行句柄 + 暂停/调试控制（docs/design/flows.md §8.4）。
 * 暂停只在节点之间生效；失败暂停（final=pause、连续失败、策略拦截）等待用户选择处理方式。
 */
internal class FlowRunHandleImpl(
    override val runId: RunId,
    private val threadId: ThreadId,
    private val store: ThreadStore,
    private val scope: CoroutineScope,
    eventReplay: Int,
    debug: Boolean,
    initialBreakpoints: Set<String>,
) : FlowRunHandle,
    ControlPort {
    private val _events = MutableSharedFlow<AgentEvent>(replay = eventReplay)
    override val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(RunState.STARTING)
    override val state: StateFlow<RunState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(FlowProgress())
    override val progress: StateFlow<FlowProgress> = _progress.asStateFlow()

    private val gate = MutableStateFlow(true)
    private val result = CompletableDeferred<RunFinished>()
    private val debugMode = debug
    private val breakpoints = MutableStateFlow(initialBreakpoints)
    private var stepping = debug && initialBreakpoints.isEmpty()
    private var failurePause: CompletableDeferred<PauseResolution>? = null
    private var job: Job? = null

    /** 运行期修订入口；解释器就绪后挂上。 */
    private var patcher: (suspend (FlowDefinition) -> Outcome<Unit>)? = null

    fun attach(job: Job) {
        this.job = job
    }

    /**
     * 挂上修订入口（docs/design/flows-precondition-selfrepair.md S3）。
     * 解释器就绪后调用；未挂上时修订请求返回"运行尚未开始"。
     */
    fun attachPatcher(patcher: suspend (FlowDefinition) -> Outcome<Unit>) {
        this.patcher = patcher
    }

    override suspend fun applyPatch(definition: FlowDefinition): Outcome<Unit> {
        val apply =
            patcher
                ?: return Outcome.Err(AppError(FlowErrorCodes.NOT_FOUND, "运行尚未开始，无法应用修订"))
        return apply(definition)
    }

    fun setState(state: RunState) {
        if (_state.value == state) return
        _state.value = state
        _events.tryEmit(AgentEvent.StateChanged(state))
    }

    suspend fun append(payload: EventPayload) {
        val event = store.append(threadId, runId, payload)
        _events.emit(AgentEvent.Persisted(event))
    }

    fun complete(finished: RunFinished) {
        result.complete(finished)
        setState(RunState.FINISHED)
    }

    // ---- RunHandle ----------------------------------------------------------------------------

    /** 流程不支持运行中补充指令（docs/design/flows.md §8.4）：空操作。 */
    override fun steer(text: String) = Unit

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ) {
        gate.value = false
        markPaused(reason, detail)
    }

    override fun resume(note: String?) {
        failurePause?.complete(PauseResolution.RETRY_NODE)
        openGate()
    }

    override fun cancel() {
        job?.cancel()
    }

    override suspend fun await(): RunFinished = result.await()

    override fun debug(): FlowDebugControl =
        object : FlowDebugControl {
            override fun step() {
                stepping = true
                openGate()
            }

            override fun resumeRun() {
                stepping = false
                openGate()
            }

            override fun setBreakpoints(nodeIds: Set<String>) {
                breakpoints.value = nodeIds
            }

            override fun resolvePause(resolution: PauseResolution) {
                failurePause?.complete(resolution)
                openGate()
            }
        }

    private fun openGate() {
        gate.value = true
        _progress.update { it.copy(paused = false, pauseReason = null, pauseDetail = null) }
        scope.launch { append(RunResumed()) }
        setState(RunState.EXECUTING_TOOLS)
    }

    private fun markPaused(
        reason: PauseReason,
        detail: String?,
    ) {
        _progress.update { it.copy(paused = true, pauseReason = reason, pauseDetail = detail) }
        setState(RunState.PAUSED)
        scope.launch { append(RunPaused(reason = reason, detail = detail)) }
    }

    // ---- ControlPort --------------------------------------------------------------------------

    override suspend fun beforeNode(
        nodeId: String,
        nodeName: String?,
    ) {
        _progress.update {
            it.copy(
                currentNodeId = nodeId,
                currentNodeName = nodeName,
                completedNodes =
                    it.completedNodes + 1,
            )
        }
        setState(RunState.EXECUTING_TOOLS)
        if (debugMode && (stepping || nodeId in breakpoints.value)) {
            gate.value = false
            markPaused(PauseReason.MANUAL, nodeId)
        }
        gate.first { it }
    }

    override suspend fun pauseOnFailure(
        reason: PauseReason,
        nodeId: String,
        detail: String?,
    ): PauseResolution = waitResolution(reason, detail)

    override suspend fun pauseOnConsecutive(
        count: Int,
        nodeId: String,
    ): PauseResolution = waitResolution(PauseReason.MANUAL, "consecutive_failures:$count")

    override suspend fun pauseOnTool(
        control: ToolControl,
        nodeId: String,
    ): PauseResolution =
        when (control) {
            is ToolControl.Pause -> waitResolution(control.reason, control.detail)
            is ToolControl.Finish -> PauseResolution.SKIP_NODE
        }

    private suspend fun waitResolution(
        reason: PauseReason,
        detail: String?,
    ): PauseResolution {
        val pending = CompletableDeferred<PauseResolution>()
        failurePause = pending
        gate.value = false
        markPaused(reason, detail)
        val resolution = pending.await()
        failurePause = null
        gate.value = true
        return resolution
    }
}
