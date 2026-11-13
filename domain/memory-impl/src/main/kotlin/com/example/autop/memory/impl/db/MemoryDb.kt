/**
 * `memory.db` 数据库（docs/tech/06 §2，ADR-0028）：`exportSchema = true`。
 * 尚未发布，表结构直接以版本 1 为准，不保留历史迁移。
 */
package com.example.autop.memory.impl.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** 记忆数据库（版本 1）。 */
@Database(
    entities = [
        MemoryNodeEntity::class,
        MemoryVersionEntity::class,
        MemoryVectorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class MemoryDb : RoomDatabase() {
    public abstract fun nodeDao(): MemoryNodeDao

    public abstract fun versionDao(): MemoryVersionDao

    public abstract fun vectorDao(): MemoryVectorDao
}
