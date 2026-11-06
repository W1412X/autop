/**
 * 对外运行服务（docs/tech/07 §1.3）：对话、任务、中断恢复与控制观察的端口门面。
 */
package com.example.autop.task

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.PlanItem
import com.example.autop.agent.RunState
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.flow.FlowDebugControl
import com.example.autop.flow.FlowProgress
import com.example.autop.flow.FlowRunOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** 运行服务：发起、控制与观察 Run（端口门面：方法数是服务面的自然结果）。 */
@Suppress("TooManyFunctions")
public interface RunService {
    /** 发起一次对话；[threadId] 为 null 时新建对话。 */
    public suspend fun startChat(
        threadId: ThreadId?,
        text: String,
        attachCurrentScreen: Boolean,
    ): Outcome<RunRecord>

    /** 从 [atSeq] 处分叉源线程并发起对话（[text] 为 null 时沿用源消息继续）。 */
    public suspend fun forkAndRun(
        threadId: ThreadId,
        atSeq: Long,
        text: String?,
    ): Outcome<RunRecord>

    /** 运行中补充指令：不打断当前采样或工具，在本轮工具结果写完后注入。 */
    public fun steer(
        runId: RunId,
        text: String,
    )

    public fun pause(runId: RunId)

    public fun resume(
        runId: RunId,
        note: String?,
    )

    public fun cancel(runId: RunId)

    /** 回答 ask_user。 */
    public suspend fun answer(
        runId: RunId,
        answer: String,
    )

    /** 立即运行一个任务（任务详情页“立即运行”，docs/tech/08 §4.3）。 */
    public suspend fun runTaskNow(task: Task): Outcome<RunRecord>

    /** 立即运行一个流程（流程页「运行」与调试，docs/design/flows.md §9.2）。 */
    public suspend fun runFlow(
        flowId: FlowId,
        params: JsonObject,
        options: FlowRunOptions,
    ): Outcome<RunRecord>

    /** 中断恢复：同一 Thread 新起 Run 接着做（docs/tech/07 §1.3、04 §3.11）。 */
    public suspend fun resumeInterrupted(runId: RunId): Outcome<RunRecord>

    /** 等待中 Run 重新入队（锁屏提醒“执行”，docs/tech/07 §4.4）。 */
    public fun requeueWaiting(runId: RunId)

    /** 等待中 Run 稍后重提醒。 */
    public fun snoozeWaiting(
        runId: RunId,
        minutes: Int,
    )

    /** 等待中 Run 跳过收口。 */
    public fun skipWaiting(runId: RunId)

    /** 流程 Run 的调试控制（单步、断点、失败暂停后的处理）；非流程 Run 或未在运行返回 null。 */
    public fun flowDebug(runId: RunId): FlowDebugControl?

    /** 流程 Run 的执行进度；非流程 Run 或未在运行返回 null。 */
    public fun flowProgress(runId: RunId): StateFlow<FlowProgress>?

    /** 当前活跃 Run（悬浮球与对话页使用）。 */
    public fun observeActive(): StateFlow<ActiveRunInfo?>

    /** Run 记录流（对话页状态条与问题卡片使用）。 */
    public fun observeRun(runId: RunId): Flow<RunRecord>

    /** 运行中的实时事件。 */
    public fun liveEvents(runId: RunId): Flow<AgentEvent>
}

/** 活跃 Run 的展示信息。 */
public data class ActiveRunInfo(
    val run: RunRecord,
    val state: RunState,
    val currentStep: String?,
    val stepCount: Int,
    val plan: List<PlanItem>,
)
