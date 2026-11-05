/**
 * AgentSession 运行入口（docs/tech/04 §2.6）：runtime 对外的启动、事件流与控制句柄。
 */
package com.example.autop.agent

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.llm.Block
import com.example.autop.llm.ModelRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 一次 Run 的启动请求。 */
public data class RunRequest(
    val runId: RunId,
    val threadId: ThreadId,
    /** 本次用户输入（对话）或任务目标渲染结果（后台）。 */
    val input: List<Block>,
    val goal: String?,
    /** 触发上下文等，供 ContextContributor 使用。 */
    val vars: Map<String, String>,
    /** 已叠加 Task/Run 覆盖的配置快照。 */
    val config: ConfigSnapshot,
    /** 允许的工具 id；null = 配置中全部启用的工具。 */
    val toolFilter: Set<String>?,
    val modelRole: ModelRole = ModelRole.MAIN,
    /** WP10：有值时先回放再进入主循环（docs/tech/04 §3.12）。 */
    val replay: ReplayPlan? = null,
)

/** Run 的状态机（docs/tech/04 §2.6）。 */
public enum class RunState {
    STARTING,
    SAMPLING,
    EXECUTING_TOOLS,
    COMPACTING,
    PAUSED,
    WAITING_USER,
    FINISHED,
}

/** 给 UI 的实时事件；持久化事件也会以 [AgentEvent.Persisted] 形式转发。 */
public sealed interface AgentEvent {
    public data class Persisted(
        val event: ThreadEvent,
    ) : AgentEvent

    public data class TextDelta(
        val text: String,
    ) : AgentEvent

    public data class ThinkingDelta(
        val text: String,
    ) : AgentEvent

    public data class ToolCallStreaming(
        val index: Int,
        val name: String,
    ) : AgentEvent

    public data class ToolProgress(
        val callId: String,
        val text: String,
    ) : AgentEvent

    public data class StateChanged(
        val state: RunState,
    ) : AgentEvent
}

/** 一次运行的控制句柄。 */
public interface RunHandle {
    public val runId: RunId

    /** 实时事件流；replay 量取配置 `agent.ui.eventReplay`。 */
    public val events: SharedFlow<AgentEvent>

    public val state: StateFlow<RunState>

    /** 运行中补充指令：不打断当前采样或工具，在本轮工具结果写完后注入。 */
    public fun steer(text: String)

    public fun pause(
        reason: PauseReason,
        detail: String? = null,
    )

    public fun resume(note: String? = null)

    public fun cancel()

    public suspend fun await(): RunFinished
}

/** agent 运行入口。 */
public interface AgentSession {
    public fun start(
        request: RunRequest,
        scope: CoroutineScope,
    ): RunHandle

    /** 在 [atSeq] 处从源线程分叉出新线程（docs/tech/04 §3.11）。 */
    public suspend fun fork(
        threadId: ThreadId,
        atSeq: Long,
        title: String?,
    ): Outcome<ThreadId>
}
