/**
 * 任务域对外服务（docs/tech/07 §1.3）：任务仓储、草稿生成、触发模拟与用户通知。
 */
package com.example.autop.task

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 任务仓储（保存即校验并重新安排闹钟）。 */
public interface TaskRepository {
    public fun observeTasks(): Flow<List<Task>>

    public suspend fun get(id: TaskId): Task?

    /** 校验 + 重新安排闹钟；不合法返回 `task.invalid`。 */
    public suspend fun save(task: Task): Outcome<Task>

    public suspend fun delete(id: TaskId)

    public suspend fun setEnabled(
        id: TaskId,
        enabled: Boolean,
    )

    /** 任务的运行记录（[taskId] 为 null 时看全部）。 */
    public fun observeRuns(
        taskId: TaskId?,
        limit: Int,
    ): Flow<List<RunRecord>>
}

/** 一句话 → 任务草稿（永远需要用户在编辑器里确认保存）。 */
public interface TaskDraftGenerator {
    public suspend fun draft(description: String): Outcome<Task>
}

/** 试运行：用最近事件验证条件任务。 */
public interface TriggerSimulator {
    public suspend fun recentEvents(
        sourceId: String,
        limit: Int,
    ): List<TriggerEvent>

    public suspend fun evaluate(
        task: Task,
        event: TriggerEvent,
    ): ConditionResult
}

/** 用户通知（由 task-impl 实现，其他领域可用）。 */
public interface UserNotifier {
    public fun notifyResult(run: RunRecord)

    public fun notifyMessage(
        title: String,
        text: String,
        runId: RunId?,
    )

    /** ask_user 等待回答，且用户不在占屏幕的运行中：发提问通知，点按打开应用。 */
    public fun notifyQuestion(
        run: RunRecord,
        question: String,
    )

    /** 提问已结束（回答、超时或运行结束）：撤掉对应通知。 */
    public fun cancelQuestion(runId: RunId)

    /** 策略拦截暂停，且用户不在占屏幕的运行中：发拦截通知，提供继续与结束操作。 */
    public fun notifyBlocked(run: RunRecord)
}

/** AgentHost 前台服务运行状态（WP11 健康检查"常驻后台"项）。 */
public interface AgentHostStatus {
    public val running: StateFlow<Boolean>
}
