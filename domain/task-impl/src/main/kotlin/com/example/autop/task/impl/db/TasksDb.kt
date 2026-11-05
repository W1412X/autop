/**
 * `tasks.db` 数据库（docs/tech/07 §3）：`exportSchema = true`，schema 导出到
 * `schemas/` 并入库；每次变更写 Migration 与测试（版本 1 起）。
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
    version = 1,
    exportSchema = true,
)
public abstract class TasksDb : RoomDatabase() {
    public abstract fun taskDao(): TaskDao

    public abstract fun runDao(): RunDao

    public abstract fun auditDao(): AuditDao

    public abstract fun triggerStateDao(): TriggerStateDao
}
