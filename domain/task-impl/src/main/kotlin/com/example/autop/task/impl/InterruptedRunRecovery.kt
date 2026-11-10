/**
 * 中断恢复（docs/tech/04 §3.11 末段，07 §1.3，WP10/WP8.4）：进程被杀留下的未收口 Run
 * 在启动时统一关闭（补写带 liveNote 的 `RunFinished`）；用户点“继续”时在同一线程
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
     * 启动收口（WP8.4）：进程被杀留下的未收口 Run 全部关闭——已启动的标 INTERRUPTED，
     * 未启动的标 CANCELLED（errorCode=LOST_ON_RESTART），并补写带 liveNote 的
     * `RunFinished`（live 由事件重建，收口事件必须在事件流里）；返回处理条数。
     */
    @Suppress("TooGenericExceptionCaught") // 单条补写失败不阻断其余恢复，异常转日志
    public suspend fun closeUnfinished(): Int {
        val unfinished = runStore.unfinished()
        for (record in unfinished) {
            val started = record.startedAt != null
            val status = if (started) RunStatus.INTERRUPTED else RunStatus.CANCELLED
            val outcome = if (started) RunOutcome.INTERRUPTED else RunOutcome.CANCELLED
            val code = if (started) null else TaskErrorCodes.LOST_ON_RESTART
            runStore.mutate(record.id) {
                it.copy(
                    status = status,
                    endedAt = clock.nowMillis(),
                    errorCode = code ?: it.errorCode,
                    pauseReason = null,
                    waitingQuestion = null,
                )
            }
            try {
                threadStore.append(
                    record.threadId,
                    record.id,
                    RunFinished(
                        status = outcome,
                        summary = null,
                        errorCode = code,
                        liveNote = prompts.runEndedNote(outcome),
                    ),
                )
            } catch (e: Exception) {
                logger.w(TAG, "append_interrupted_event_failed", "runId" to record.id.value, error = e)
            }
        }
        if (unfinished.isNotEmpty()) {
            logger.i(TAG, "unfinished_runs_closed", "count" to unfinished.size)
        }
        return unfinished.size
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
