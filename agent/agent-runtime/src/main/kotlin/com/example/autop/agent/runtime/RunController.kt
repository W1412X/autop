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

    /** 暂停置位：门关闭期间 [setState] 吞掉执行态迁移（在途采样/批次不得把 PAUSED 翻回执行态）。 */
    @Volatile
    private var pausedFlag: Boolean = false

    /** 经观察包装的存储：工具经 ToolContext 写入的事件同样转为 Persisted。 */
    val toolStore: ThreadStore =
        ObservedThreadStore(store) { event -> _events.emit(AgentEvent.Persisted(event)) }

    fun attach(job: Job) {
        this.job = job
    }

    fun setState(state: RunState) {
        if (_state.value == state) return
        // 暂停门关闭期间吞掉执行态迁移：否则在途阶段（采样完成进工具批次等）会把
        // PAUSED 翻回执行态，状态/触摸盾/控制条跟着"复活"，实际循环仍停在 awaitOpen。
        if (pausedFlag && state in RunState.EXECUTION_STATES) return
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
        pausedFlag = true
        gate.value = false
        setState(RunState.PAUSED)
        launchLifecycle { append(RunPaused(reason = reason, detail = detail)) }
    }

    /**
     * 恢复（N192：note 与恢复提示排队而非直接落盘）：直接 append 会插进未应答的
     * ToolCall 与 ToolResult 之间，下一轮请求触发 MsgValidator
     * "user message before previous tool calls were answered" 崩溃；
     * 排入 steer 队列后统一在工具批次间落盘（TurnLoop 每轮采样前 drain）。
     * note 必须**同步**入队（若放在 scope.launch 里会与 drain 竞态迟到/丢失）。
     */
    override fun resume(note: String?) {
        // 同步清暂停置位（与 pause 交错时"最后一下点击赢"）：其后的暂停会重新置位，
        // 本次收尾随之作废；note 仍同步入队（上注）。
        pausedFlag = false
        if (!note.isNullOrBlank()) {
            steers.send(note, UserMessageKind.STEER)
        }
        launchLifecycle {
            // 恢复未落地就又被暂停：整段收尾作废（不落 RunResumed、不开门）。
            if (pausedFlag) return@launchLifecycle
            append(RunResumed(userNote = note))
            // 恢复提示：要求模型先观察屏幕再行动（docs/tech/04 §3.2 要点）
            val reobserve = prompts.render(ID_REOBSERVE, emptyMap()).getOrNull()?.text
            if (reobserve != null) {
                steers.send(reobserve, UserMessageKind.INJECTED)
            }
            // gate 最后开且复核暂停置位：保证 RunResumed/恢复提示先于循环继续落盘，
            // 且收尾期间落下的暂停不被开门覆盖。
            setState(RunState.STARTING)
            if (!pausedFlag) gate.value = true
        }
    }

    /**
     * 暂停/恢复的落盘串行：后一条必须等前一条落盘完成再写。否则 RunResumed 可能先于 RunPaused 落盘，
     * 暂停横幅会残留（且无法被移除）。
     */
    private val lifecycleLock = Any()
    private var lifecycleTail: Job? = null

    private fun launchLifecycle(block: suspend () -> Unit) {
        synchronized(lifecycleLock) {
            val previous = lifecycleTail
            lifecycleTail =
                scope.launch {
                    previous?.join()
                    block()
                }
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
