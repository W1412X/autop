/**
 * Run 调度（docs/tech/07 §4.3）：门条件检查（无障碍/锁屏/屏幕租约/最近触摸）
 * 与等待中 Run 的控制（重新入队、跳过、过期）。
 */
package com.example.autop.task.impl

import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ScreenLease
import com.example.autop.device.UserActivityMonitor
import com.example.autop.task.LockedBehavior
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.core.RunQueue
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.reminder.LockReminder
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Run 调度器（供 DI 装配）。 */
@Suppress("LongParameterList") // 调度面端口装配（DI 注入）的自然结果
public class RunScheduler(
    private val runStore: RunStore,
    private val queue: RunQueue,
    private val pending: PendingRuns,
    private val runner: RunRunner,
    private val screenLease: ScreenLease,
    private val accessibilityStatus: AccessibilityStatus,
    private val deviceState: DeviceStateProvider,
    private val userActivity: UserActivityMonitor,
    private val lockReminder: LockReminder,
    private val notifier: UserNotifierImpl,
    private val configProvider: ConfigProvider,
    private val threadStore: ThreadStore,
    private val prompts: PromptRepository,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val scheduleMutex = Mutex()

    init {
        // 门条件变化时重试调度（docs/tech/07 §4.3：租约释放/解锁/无障碍连上）。
        scope.launch { accessibilityStatus.connected.collect { if (it) trySchedule() } }
        scope.launch { screenLease.holder.collect { if (it == null) trySchedule() } }
        scope.launch { deviceState.changes.collect { if (!it.keyguardLocked) trySchedule() } }
    }

    /** 单协程串行，避免调度竞态（docs/tech/07 §4.3）。 */
    @Suppress("ReturnCount") // 门条件逐条早退是调度逻辑的自然结构（07 §4.3 伪代码同构）
    public fun trySchedule() {
        scope.launch {
            scheduleMutex.withLock {
                val next = queue.peek() ?: return@withLock
                val record = runStore.get(next.runId)
                if (record == null) {
                    queue.remove(next)
                    return@withLock
                }
                val task = pending.taskOf(record.id)
                val needsScreen = pending.flowNeedsScreen(record.id) ?: task?.needsScreen ?: true
                if (needsScreen && !accessibilityStatus.connected.value) {
                    notifier.notifyAccessibilityNeeded(ACCESSIBILITY_HINT)
                    markWaiting(record, RunStatus.WAITING_SCREEN)
                    return@withLock
                }
                if (needsScreen && deviceState.current().keyguardLocked) {
                    gateLocked(record, task)
                    return@withLock
                }
                if (needsScreen && screenLease.holder.value != null) {
                    markWaiting(record, RunStatus.WAITING_SCREEN)
                    return@withLock
                }
                val idle = idleDelayMs(record, needsScreen)
                if (idle > 0) {
                    scope.launch {
                        delay(idle)
                        trySchedule()
                    }
                    return@withLock
                }
                queue.remove(next)
                val finalRecord = record
                val finalTask = task
                scope.launch {
                    runner.execute(finalRecord, finalTask)
                    trySchedule()
                }
            }
        }
    }

    /** RunControl：重新入队并立即调度（解锁后执行）。 */
    public fun requeue(runId: RunId) {
        scope.launch {
            lockReminder.dismiss(runId)
            val record = runStore.get(runId) ?: return@launch
            runStore.mutate(runId) { it.copy(status = RunStatus.QUEUED) }
            enqueue(record)
            trySchedule()
        }
    }

    /** 入队（origin 权重已在 RunQueue 内叠加，这里补 Task.priority，越大越先）。 */
    public fun enqueue(record: RunRecord) {
        queue.add(
            RunQueue.Entry(
                runId = record.id,
                origin = record.origin,
                taskPriority = pending.taskOf(record.id)?.priority ?: 0,
                enqueuedAt = clock.nowMillis(),
            ),
        )
    }

    /** 取消尚未启动的 Run。 */
    public fun cancelQueued(runId: RunId) {
        scope.launch { cancelWaitingRun(runId) }
    }

    /**
     * 跳过/过期收口（Run → CANCELLED/EXPIRED）：改 runStore 后补写
     * `RunFinished`（带 liveNote），同线程后续 Run 的模型能看到上一个任务没有完成（WP8.2）。
     */
    @Suppress("TooGenericExceptionCaught") // 补写失败只记日志，收口不被单条事件写入阻断
    public suspend fun cancelWaitingRun(
        runId: RunId,
        status: RunStatus = RunStatus.CANCELLED,
        errorCode: String? = null,
    ) {
        lockReminder.dismiss(runId)
        queue.snapshot().firstOrNull { it.runId == runId }?.let { queue.remove(it) }
        pending.remove(runId)
        runStore.mutate(runId) {
            it.copy(status = status, endedAt = clock.nowMillis(), errorCode = errorCode ?: it.errorCode)
        }
        val record = runStore.get(runId)
        if (record != null) {
            val outcome =
                when (status) {
                    RunStatus.EXPIRED -> RunOutcome.EXPIRED
                    else -> RunOutcome.CANCELLED
                }
            try {
                threadStore.append(
                    record.threadId,
                    runId,
                    RunFinished(
                        status = outcome,
                        summary = null,
                        errorCode = errorCode,
                        liveNote = prompts.runEndedNote(outcome),
                    ),
                )
            } catch (e: Exception) {
                logger.w(TAG, "append_waiting_finish_failed", "runId" to runId.value, error = e)
            }
        }
        logger.d(TAG, "waiting_run_closed", "runId" to runId.value, "status" to status.name)
    }

    /** 锁屏门：SKIP → CANCELLED；REMIND → 提醒并等待（docs/tech/07 §4.3/§4.4）。 */
    private suspend fun gateLocked(
        record: RunRecord,
        task: Task?,
    ) {
        if (task?.lockedBehavior == LockedBehavior.SKIP) {
            cancelWaitingRun(record.id, RunStatus.CANCELLED, TaskErrorCodes.LOCKED)
        } else {
            markWaiting(record, RunStatus.WAITING_USER)
            lockReminder.remind(record)
        }
    }

    /** 用户最近触摸后的静默期（只对非对话且需要屏幕的 Run 生效）。 */
    private fun idleDelayMs(
        record: RunRecord,
        needsScreen: Boolean,
    ): Long {
        val lastTouch = userActivity.lastUserTouchAt()
        return if (!needsScreen || record.origin == RunOrigin.CHAT || lastTouch == null) {
            0
        } else {
            val idleMs = configProvider.current()[TaskConfig.TASK].idleBeforeStartMs
            (lastTouch + idleMs - clock.nowMillis()).coerceAtLeast(0)
        }
    }

    private suspend fun markWaiting(
        record: RunRecord,
        status: RunStatus,
    ) {
        runStore.mutate(record.id) { it.copy(status = status) }
    }

    private companion object {
        const val TAG = "RunScheduler"
        const val ACCESSIBILITY_HINT = "无障碍服务未开启，无法操作屏幕"
    }
}
