/**
 * 运行服务实现（docs/tech/07 §4.3）：对话（CHAT/FORK）与任务（TASK_MANUAL/
 * SCHEDULE/CONDITION/RESUME）。排队与执行在 [RunExecutor]，观察与收口在
 * [RunObserver]，本类只做接线与控制委托，并作为 TriggerHub 的入队端口。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.PauseReason
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.core.common.AppError
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.device.TouchShield
import com.example.autop.flow.FlowDebugControl
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowProgress
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowValidationService
import com.example.autop.llm.Block
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.ImageAttachment
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.impl.host.AgentHostStarter
import com.example.autop.task.impl.schedule.TriggerRunner
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/** 运行协调器：实现 [RunService] 与 [TriggerRunner]。 */
@Suppress("TooManyFunctions", "LongParameterList") // RunService 接口面 + 端口装配（DI 注入）的自然结果
public class RunCoordinator(
    private val executor: RunExecutor,
    private val observer: RunObserver,
    private val interaction: ChatUserInteraction,
    private val host: AgentHostStarter,
    private val control: RunControl,
    private val recovery: InterruptedRunRecovery,
    private val shield: TouchShield,
    private val logger: Logger,
    private val flowStore: FlowStore,
    private val flowValidation: FlowValidationService,
    private val runStore: RunStore,
    private val threadStore: ThreadStore,
) : RunService,
    TriggerRunner {
    override suspend fun startChat(
        threadId: ThreadId?,
        text: String,
        attachCurrentScreen: Boolean,
        images: List<ImageAttachment>,
    ): Outcome<RunRecord> {
        val result = executor.launchChat(threadId, text, attachCurrentScreen, images)
        host.ensureRunning()
        return result
    }

    override suspend fun forkAndRun(
        threadId: ThreadId,
        atSeq: Long,
        text: String?,
    ): Outcome<RunRecord> {
        val result = executor.launchFork(threadId, atSeq, text)
        host.ensureRunning()
        return result
    }

    /** 任务页“立即运行”/试运行（origin=TASK_MANUAL）。 */
    override suspend fun runTaskNow(task: Task): Outcome<RunRecord> {
        val result = executor.launchTask(task, RunOrigin.TASK_MANUAL, emptyMap())
        host.ensureRunning()
        return result
    }

    /** 流程页「运行」与调试（origin=TASK_MANUAL）。 */
    override suspend fun runFlow(
        flowId: FlowId,
        params: JsonObject,
        options: FlowRunOptions,
    ): Outcome<RunRecord> {
        val stored =
            flowStore.get(flowId)
                ?: return Outcome.Err(
                    AppError(code = FlowErrorCodes.NOT_FOUND, message = "flow not found: ${flowId.value}"),
                )
        val needsScreen = flowValidation.needsScreen(stored.definition)
        val result = executor.launchFlow(stored.definition, params, options, needsScreen)
        host.ensureRunning()
        return result
    }

    /** 中断恢复（docs/tech/07 §1.3、04 §3.11）：同 Thread 新起 Run 接着做。 */
    override suspend fun resumeInterrupted(runId: RunId): Outcome<RunRecord> {
        val result = recovery.resume(runId)
        host.ensureRunning()
        return result
    }

    override fun requeueWaiting(runId: RunId) {
        control.requeueNow(runId)
    }

    override fun snoozeWaiting(
        runId: RunId,
        minutes: Int,
    ) {
        control.snooze(runId, minutes)
    }

    override fun skipWaiting(runId: RunId) {
        control.cancelWaiting(runId)
    }

    /** TriggerHub 入队端口（docs/tech/07 §4.2）。 */
    override suspend fun enqueueTask(
        task: Task,
        origin: RunOrigin,
        vars: Map<String, String>,
    ) {
        executor.launchTask(task, origin, vars)
        host.ensureRunning()
    }

    /**
     * 补充指令（WP8.5）：运行中的 Run 走 SteerQueue（不插进未配对的 ToolCall/ToolResult
     * 之间）；排队中（未启动）的 Run 直接落盘为 STEER 用户消息，Run 开始后渲染 live 时
     * 自然可见（顺序在 INPUT 之后）；已结束的 Run 报 `task.invalid`。
     */
    override suspend fun steer(
        runId: RunId,
        text: String,
    ): Outcome<Unit> {
        val handle = observer.handleOf(runId)
        if (handle != null) {
            handle.steer(text)
            return Outcome.Ok(Unit)
        }
        val record =
            runStore.get(runId)
                ?: return Outcome.Err(
                    AppError(code = TaskErrorCodes.NOT_FOUND, message = "run not found: ${runId.value}"),
                )
        return if (record.status in QUEUED_STEERABLE) {
            threadStore.append(
                record.threadId,
                runId,
                UserMessage(blocks = listOf(Block.Text(text)), kind = UserMessageKind.STEER),
            )
            Outcome.Ok(Unit)
        } else {
            Outcome.Err(AppError(code = TaskErrorCodes.INVALID, message = "run is not active"))
        }
    }

    override fun pause(runId: RunId) {
        val handle = observer.handleOf(runId)
        if (handle == null) {
            logger.w(TAG, "pause_no_active_run", "runId" to runId.value)
            return
        }
        handle.pause(PauseReason.MANUAL)
    }

    override fun resume(
        runId: RunId,
        note: String?,
    ) {
        val handle = observer.handleOf(runId)
        if (handle == null) {
            logger.w(TAG, "resume_no_active_run", "runId" to runId.value)
            return
        }
        handle.resume(note)
    }

    override fun cancel(runId: RunId) {
        // N191（安全关键）：先强制解除触摸屏蔽再取消——中止按钮点下就必须能操作手机，
        // 即使 Run 卡死未收口（新 Run 开始时 reset 恢复正常驱动）。
        shield.forceRelease()
        val handle = observer.handleOf(runId)
        if (handle != null) {
            handle.cancel()
            return
        }
        // 句柄未登记（排队/启动窗口）：先同步落取消意图再收口，末尾补查一次句柄——
        // begin 可能恰在上面判断后落地；意图由执行侧 begin 后消费或此处补投（取消幂等）。
        executor.markCancelled(runId)
        executor.cancelQueued(runId)
        observer.handleOf(runId)?.cancel()
    }

    override suspend fun answer(
        runId: RunId,
        answer: String,
    ) {
        interaction.answer(runId, answer)
    }

    override fun flowDebug(runId: RunId): FlowDebugControl? = (observer.handleOf(runId) as? FlowRunHandle)?.debug()

    override fun flowProgress(runId: RunId): StateFlow<FlowProgress>? =
        (observer.handleOf(runId) as? FlowRunHandle)?.progress

    override fun observeActive(): StateFlow<ActiveRunInfo?> = observer.active

    override fun observeRun(runId: RunId): Flow<RunRecord> = observer.observeRun(runId)

    /** 线程未收口的 Run（WP8.5）：最近创建的一个开放状态 Run，没有则 null。 */
    override fun observeOpenRun(threadId: ThreadId): Flow<RunRecord?> =
        runStore.observeThread(threadId).map { records ->
            records.filter { it.status in RunStatus.OPEN }.maxByOrNull { it.createdAt }
        }

    override fun liveEvents(runId: RunId): SharedFlow<AgentEvent> = observer.eventsOf(runId)

    private companion object {
        const val TAG = "RunCoordinator"

        /** 可直接落盘 steer 的未启动状态（WP8.5）。 */
        val QUEUED_STEERABLE: Set<RunStatus> = setOf(RunStatus.QUEUED, RunStatus.WAITING_SCREEN, RunStatus.WAITING_USER)
    }
}
