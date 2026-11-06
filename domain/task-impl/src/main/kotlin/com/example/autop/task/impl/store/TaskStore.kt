/**
 * 任务仓储实现（docs/tech/07 §1.3/§3）：Task 整体以 JSON 存入 `tasks` 表，
 * 编解码见 [TaskJsonCodec]；保存即校验并通知调度重排。
 */
package com.example.autop.task.impl.store

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.RunRecord
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TaskRepository
import com.example.autop.task.core.TaskValidator
import com.example.autop.task.impl.db.TasksDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 任务仓储实现。 */
public class TaskStore(
    private val db: TasksDb,
    private val runStore: RunStore,
    private val configProvider: ConfigProvider,
    private val knownSources: () -> Set<String>,
    private val codec: TaskJsonCodec = TaskJsonCodec(),
    /** 任务删除后的清理（如流程状态 	ask:<id> 作用域）。 */
    private val onDeleted: suspend (TaskId) -> Unit = {},
) : TaskRepository {
    /** 保存/删除后的重排信号（AlarmScheduler 订阅）。 */
    public val scheduleChanges: MutableStateFlow<Long> = MutableStateFlow(0)

    override fun observeTasks(): Flow<List<Task>> =
        db.taskDao().observeAll().map { list -> list.mapNotNull { codec.decode(it) } }

    override suspend fun get(id: TaskId): Task? = db.taskDao().byId(id.value)?.let { codec.decode(it) }

    /** 已启用的定时/条件任务（重排与 TriggerHub 用）。 */
    public suspend fun enabledTasks(): List<Task> = db.taskDao().enabledOnce().mapNotNull { codec.decode(it) }

    override suspend fun save(task: Task): Outcome<Task> {
        val limits = configProvider.current()[TaskConfig.LIMITS]
        val problems =
            TaskValidator.validate(
                task = task,
                knownSources = knownSources(),
                minCooldownMs = limits.minCooldownMs,
                maxDailyLimit = limits.maxDailyLimit,
            )
        if (!problems.isValid) {
            return Outcome.Err(
                com.example.autop.core.common.AppError(
                    code = TaskErrorCodes.INVALID,
                    message = "invalid task: ${problems.byField.keys}",
                ),
            )
        }
        db.taskDao().upsert(codec.toEntity(task))
        bumpSchedule()
        return Outcome.Ok(task)
    }

    override suspend fun delete(id: TaskId) {
        db.taskDao().delete(id.value)
        db.triggerStateDao().delete(id.value)
        onDeleted(id)
        bumpSchedule()
    }

    override suspend fun setEnabled(
        id: TaskId,
        enabled: Boolean,
    ) {
        val task = get(id) ?: return
        db.taskDao().upsert(codec.toEntity(task.copy(enabled = enabled, updatedAt = System.currentTimeMillis())))
        bumpSchedule()
    }

    override fun observeRuns(
        taskId: TaskId?,
        limit: Int,
    ): Flow<List<RunRecord>> = runStore.observeRuns(taskId, limit)

    private fun bumpSchedule() {
        scheduleChanges.value = System.currentTimeMillis()
    }
}
