/**
 * Run 记录（docs/tech/07 §1.2）。
 */
package com.example.autop.task

import com.example.autop.agent.PauseReason
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import kotlinx.serialization.Serializable

/** 一次运行的持久记录。 */
@Serializable
public data class RunRecord(
    val id: RunId,
    val taskId: TaskId?,
    val threadId: ThreadId,
    val origin: RunOrigin,
    val status: RunStatus,
    /** trigger.* 变量。 */
    val triggerContext: Map<String, String>,
    val createdAt: Long,
    val startedAt: Long?,
    val endedAt: Long?,
    val summary: String?,
    val errorCode: String?,
    val pauseReason: PauseReason?,
    val waitingQuestion: String?,
    /** ask_user 的选项（对话页 AskCard 的选项 Chip；docs/tech/08 §4.2）。 */
    val waitingOptions: List<String> = emptyList(),
)

/** Run 的来源。 */
@Serializable
public enum class RunOrigin {
    CHAT,
    TASK_MANUAL,
    SCHEDULE,
    CONDITION,
    RESUME,
    FORK,
}

/** Run 的生命周期状态。 */
@Serializable
public enum class RunStatus {
    QUEUED,
    WAITING_SCREEN,
    WAITING_USER,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    INTERRUPTED,
    BUDGET_EXHAUSTED,
}
