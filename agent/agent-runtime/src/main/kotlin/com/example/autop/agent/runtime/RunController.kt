/**
 * Run 控制句柄（docs/tech/04 §2.6、§3.2 要点）：事件流、状态、steer 队列、
 * 暂停门与取消。所有持久化事件经 ObservedThreadStore 转发为 Persisted。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunState
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.UserMessageKind
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
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
import kotlinx.coroutines.launch

/** 一次 Run 的控制句柄实现。 */
internal class RunController(
    private val req: RunRequest,
    store: ThreadStore,
    private val scope: CoroutineScope,
    eventReplay: Int,
    private val prompts: PromptRepository,
) : RunHandle {
    override val runId = req.runId

    /** Run 所属线程（CancelPairing/append 等扩展函数用）。 */
    val threadId: ThreadId = req.threadId

    private val _events = MutableSharedFlow<AgentEvent>(replay = eventReplay)
    override val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(RunState.STARTING)
    override val state: StateFlow<RunState> = _state.asStateFlow()

    internal val steers = SteerQueue()
    private val gate = MutableStateFlow(true)
    private val result = CompletableDeferred<RunFinished>()
    private var job: Job? = null

    /** 经观察包装的存储：工具经 ToolContext 写入的事件同样转为 Persisted。 */
    val toolStore: ThreadStore =
        ObservedThreadStore(store) { event -> _events.emit(AgentEvent.Persisted(event)) }

    fun attach(job: Job) {
        this.job = job
    }

    fun setState(state: RunState) {
        if (_state.value == state) return
        _state.value = state
        _events.tryEmit(AgentEvent.StateChanged(state))
    }

    fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }

    /** 暂停门：关闭时挂起，Run 结束或取消时抛出。 */
    suspend fun awaitOpen() {
        gate.first { it }
    }

    fun complete(event: RunFinished) {
        result.complete(event)
        setState(RunState.FINISHED)
    }

    override fun steer(text: String) {
        steers.send(text)
    }

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ) {
        gate.value = false
        setState(RunState.PAUSED)
        scope.launch { append(RunPaused(reason = reason, detail = detail)) }
    }

    /**
     * 恢复（N192：note 与恢复提示排队而非直接落盘）：直接 append 会插进未应答的
     * ToolCall 与 ToolResult 之间，下一轮请求触发 MsgValidator
     * "user message before previous tool calls were answered" 崩溃；
     * 排入 steer 队列后统一在工具批次间落盘（TurnLoop 每轮采样前 drain）。
     * note 必须**同步**入队（若放在 scope.launch 里会与 drain 竞态迟到/丢失）。
     */
    override fun resume(note: String?) {
        if (!note.isNullOrBlank()) {
            steers.send(note, UserMessageKind.STEER)
        }
        scope.launch {
            append(RunResumed(userNote = note))
            // 恢复提示：要求模型先观察屏幕再行动（docs/tech/04 §3.2 要点）
            val reobserve = prompts.render(ID_REOBSERVE, emptyMap()).getOrNull()?.text
            if (reobserve != null) {
                steers.send(reobserve, UserMessageKind.INJECTED)
            }
            setState(RunState.STARTING)
            gate.value = true
        }
    }

    override fun cancel() {
        job?.cancel()
    }

    override suspend fun await(): RunFinished = result.await()

    private companion object {
        const val ID_REOBSERVE = "agent.resume.reobserve"
    }
}
