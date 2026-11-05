/**
 * `memory.db` 数据库（docs/tech/06 §2）：`exportSchema = true`，版本 1 起每次变更配迁移。
 */
package com.example.autop.memory.impl.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** 记忆数据库（版本 1）。 */
@Database(
    entities = [
        MemoryNodeEntity::class,
        MemoryVersionEntity::class,
        MemoryFtsEntity::class,
        MemoryVectorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class MemoryDb : RoomDatabase() {
    public abstract fun nodeDao(): MemoryNodeDao

    public abstract fun versionDao(): MemoryVersionDao

    public abstract fun ftsDao(): MemoryFtsDao

    public abstract fun vectorDao(): MemoryVectorDao
}
