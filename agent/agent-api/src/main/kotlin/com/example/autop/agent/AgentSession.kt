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
    /** 尚未写入事件日志的输入；调度层已落盘时传空（WP8.1：输入在入队时写入事件日志）。 */
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
    ;

    public companion object {
        /**
         * 执行态集合（触摸盾屏蔽集合同源）：暂停门关闭期间 `RunController.setState` 吞掉
         * 向这些状态的迁移——在途采样/批次不得把 PAUSED 翻回执行态。
         */
        public val EXECUTION_STATES: Set<RunState> = setOf(STARTING, SAMPLING, EXECUTING_TOOLS, COMPACTING)
    }
}

/** 给 UI 的实时事件；持久化事件也会以 [AgentEvent.Persisted] 形式转发。 */
public sealed interface AgentEvent {
    public data class Persisted(
        val event: ThreadEvent,
    ) : AgentEvent

    /** 流式采样中途失败、即将重试：UI 应丢弃本轮已显示的增量（WP6.6）。 */
    public data class SampleRetrying(
        val attempt: Int,
        val code: String,
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
        /** 真实 callId（原生流式阶段为 null/空，解析后带上，UI 据此与结果配对转态）。 */
        val callId: String = "",
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
