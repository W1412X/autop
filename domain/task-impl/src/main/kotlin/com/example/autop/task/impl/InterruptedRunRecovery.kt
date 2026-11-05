/**
 * 中断恢复（docs/tech/04 §3.11 末段，07 §1.3，WP10）：进程被杀留下的 RUNNING
 * 标记为 INTERRUPTED（补写 `RunFinished(INTERRUPTED)`）；用户点“继续”时在同一线程
 * 新起一个 Run，input 为模板 `agent.resume.after_interrupt`。
 */
package com.example.autop.task.impl

import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.impl.store.RunStore

/** 中断恢复（供 DI 装配）。 */
@Suppress("LongParameterList") // 端口装配面（DI 注入）的自然结果
public class InterruptedRunRecovery(
    private val runStore: RunStore,
    private val threadStore: ThreadStore,
    private val prompts: PromptRepository,
    private val executor: RunExecutor,
    private val clock: Clock,
    private val logger: Logger,
) {
    /**
     * 启动时把残留 RUNNING 标记为 INTERRUPTED 并补写 `RunFinished(INTERRUPTED)`
     * （live 由事件重建，收口事件必须在事件流里）；返回处理条数。
     */
    @Suppress("TooGenericExceptionCaught") // 单条补写失败不阻断其余恢复，异常转日志
    public suspend fun markInterrupted(): Int {
        val stuck = runStore.stuckRunning()
        for (record in stuck) {
            runStore.mutate(record.id) { it.copy(status = RunStatus.INTERRUPTED, endedAt = clock.nowMillis()) }
            try {
                threadStore.append(
                    record.threadId,
                    record.id,
                    RunFinished(status = RunOutcome.INTERRUPTED, summary = null),
                )
            } catch (e: Exception) {
                logger.w(TAG, "append_interrupted_event_failed", "runId" to record.id.value, error = e)
            }
        }
        if (stuck.isNotEmpty()) {
            logger.i(TAG, "runs_marked_interrupted", "count" to stuck.size)
        }
        return stuck.size
    }

    /** 用户点“继续”：同 Thread 新起 Run 接着做（docs/tech/04 §3.11）。 */
    @Suppress("ReturnCount") // 恢复资格逐条早退（找不到/非中断/模板缺）是判定链的自然结构
    public suspend fun resume(runId: RunId): Outcome<RunRecord> {
        val record = runStore.get(runId)
        if (record == null) {
            return Outcome.Err(AppError(code = TaskErrorCodes.NOT_FOUND, message = "run not found: ${runId.value}"))
        }
        if (record.status != RunStatus.INTERRUPTED) {
            return Outcome.Err(AppError(code = TaskErrorCodes.INVALID, message = "run is not interrupted"))
        }
        val text =
            prompts
                .render(ID_RESUME_AFTER_INTERRUPT)
                .getOrNull()
                ?.text
                ?: return Outcome.Err(AppError(code = CommonErrorCodes.UNKNOWN, message = "prompt missing"))
        return executor.launchResume(record, text)
    }

    private companion object {
        const val TAG = "InterruptedRunRecovery"
        const val ID_RESUME_AFTER_INTERRUPT = "agent.resume.after_interrupt"
    }
}
