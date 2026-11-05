/**
 * `tasks.db` 访问对象（docs/tech/07 §3）：Run/Audit 为 WP6b 实际使用面，
 * Task 供 AgentHost 空闲通知计数与后续任务编辑器使用。
 */
package com.example.autop.task.impl.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 任务表访问。 */
@Dao
public interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    public suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    public fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE enabled = 1")
    public fun observeEnabled(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE enabled = 1")
    public suspend fun enabledOnce(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE enabled = 1")
    public suspend fun countEnabled(): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    public suspend fun delete(id: String)
}

/** 运行记录表访问。 */
@Dao
public interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    public suspend fun byId(id: String): RunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    public fun observe(id: String): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE threadId = :threadId ORDER BY createdAt ASC")
    public fun byThread(threadId: String): Flow<List<RunEntity>>

    /** 任务的运行记录（[taskId] 为 null 看全部）。 */
    @Query(
        "SELECT * FROM runs WHERE (:taskId IS NULL OR taskId = :taskId) " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    public fun observeRuns(
        taskId: String?,
        limit: Int,
    ): Flow<List<RunEntity>>

    /** 未收口的 Run（排队/等待/运行/暂停），AgentHost 常驻条件使用。 */
    @Query("SELECT * FROM runs WHERE status IN ('QUEUED','WAITING_SCREEN','WAITING_USER','RUNNING','PAUSED')")
    public fun observeUnfinished(): Flow<List<RunEntity>>

    /** 进程被杀残留的 RUNNING（docs/tech/04 §3.11 中断恢复用）。 */
    @Query("SELECT * FROM runs WHERE status = 'RUNNING'")
    public suspend fun stuckRunning(): List<RunEntity>
}

/** 审计日志表访问。 */
@Dao
public interface AuditDao {
    @Insert
    public suspend fun insert(entity: AuditEntity)

    @Query("SELECT * FROM audit_log WHERE runId = :runId ORDER BY createdAt ASC")
    public fun byRun(runId: String): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    public fun page(
        offset: Int,
        limit: Int,
    ): Flow<List<AuditEntity>>

    @Query("DELETE FROM audit_log WHERE createdAt < :cutoff")
    public suspend fun deleteOlderThan(cutoff: Long): Int
}

/** 触发状态表访问（冷却、每日上限、去重窗口）。 */
@Dao
public interface TriggerStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: TriggerStateEntity)

    @Query("SELECT * FROM trigger_state WHERE taskId = :taskId")
    public suspend fun byTask(taskId: String): TriggerStateEntity?

    @Query("DELETE FROM trigger_state WHERE taskId = :taskId")
    public suspend fun delete(taskId: String)
}
