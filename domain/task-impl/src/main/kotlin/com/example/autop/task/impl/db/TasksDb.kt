/**
 * `tasks.db` 数据库（docs/tech/07 §3）：`exportSchema = true`，schema 导出到
 * `schemas/` 并入库；结构变化升高 TASKS_DB_VERSION，启动检查据此提示备份或清除（ADR-0032）。
 */
package com.example.autop.task.impl.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** 任务域数据库（版本 1，四表一次建全）。 */
@Database(
    entities = [
        TaskEntity::class,
        RunEntity::class,
        TriggerStateEntity::class,
        AuditEntity::class,
    ],
    version = TASKS_DB_VERSION,
    exportSchema = true,
)
public abstract class TasksDb : RoomDatabase() {
    public abstract fun taskDao(): TaskDao

    public abstract fun runDao(): RunDao

    public abstract fun auditDao(): AuditDao

    public abstract fun triggerStateDao(): TriggerStateDao
}

/** tasks.db（任务与运行） 结构版本（ADR-0032）：结构变化时升高，启动检查据此提示备份或清除。 */
public const val TASKS_DB_VERSION: Int = 1
