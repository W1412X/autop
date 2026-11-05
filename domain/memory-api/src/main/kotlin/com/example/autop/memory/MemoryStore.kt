/**
 * 记忆存储端口（docs/tech/06 §1）。实现见 memory-impl。
 */
package com.example.autop.memory

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import kotlinx.coroutines.flow.Flow

/** 记忆存储。 */
@Suppress("TooManyFunctions") // 接口的方法面就是存储的完整操作集，拆接口反而碎
public interface MemoryStore {
    public suspend fun get(path: MemoryPath): MemoryNode?

    public suspend fun children(
        path: MemoryPath,
        offset: Int,
        limit: Int,
    ): List<MemoryNode>

    public suspend fun write(w: MemoryWrite): Outcome<WriteResult>

    public suspend fun delete(
        path: MemoryPath,
        recursive: Boolean,
        source: MemorySource,
        runId: RunId?,
    ): Outcome<Int>

    public suspend fun move(
        from: MemoryPath,
        to: MemoryPath,
    ): Outcome<Unit>

    public suspend fun versions(
        path: MemoryPath,
        limit: Int,
    ): List<MemoryVersion>

    public suspend fun revert(versionId: Long): Outcome<WriteResult>

    public suspend fun recentChanges(
        offset: Int,
        limit: Int,
        source: MemorySource?,
    ): List<MemoryVersion>

    public suspend fun changesOfRun(runId: RunId): List<MemoryVersion>

    public suspend fun revertRun(runId: RunId): Outcome<Int>

    /** 任意变更时发出（UI 刷新用）。 */
    public fun observeTree(): Flow<Unit>

    /** 用户确认 agent 写入的内容 → source 变为 USER。 */
    public suspend fun markConfirmed(path: MemoryPath)
}
