/**
 * `threads.db` 数据库（docs/tech/04 §4）：`exportSchema = true`，schema 导出到
 * `schemas/` 并入库；每次变更写 Migration 与测试（版本 1 起）。
 */
package com.example.autop.agent.store.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** 线程存储数据库（版本 1）。 */
@Database(
    entities = [
        ThreadEntity::class,
        ThreadEventEntity::class,
        LatestBaselineEntity::class,
        ThreadEventFtsEntity::class,
        ThreadEventVectorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class ThreadsDb : RoomDatabase() {
    public abstract fun threadDao(): ThreadDao

    public abstract fun eventDao(): EventDao

    public abstract fun baselineDao(): BaselineDao

    public abstract fun ftsDao(): FtsDao

    public abstract fun vectorDao(): VectorDao
}
