/**
 * `threads.db` 数据库（docs/tech/04 §4）：`exportSchema = true`，schema 导出到 `schemas/` 并入库。
 * 当前为全新安装的单一 schema（ADR-0021），不保留旧版本的迁移。
 */
package com.example.autop.agent.store.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** 线程存储数据库：分支、事件树与检索索引。 */
@Database(
    entities = [
        ThreadEntity::class,
        ThreadEventEntity::class,
        ThreadEventFtsEntity::class,
        ThreadEventVectorEntity::class,
    ],
    version = THREADS_DB_VERSION,
    exportSchema = true,
)
public abstract class ThreadsDb : RoomDatabase() {
    public abstract fun threadDao(): ThreadDao

    public abstract fun eventDao(): EventDao

    public abstract fun ftsDao(): FtsDao

    public abstract fun vectorDao(): VectorDao
}

/** threads.db（线程） 结构版本（ADR-0032）：结构变化时升高，启动检查据此提示备份或清除。 */
public const val THREADS_DB_VERSION: Int = 2
