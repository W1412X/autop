/**
 * 记忆页单测共用假件（N184）：可换数据、可发树变更信号的 [MemoryStore] 假实现，
 * 供 Browse / Changes 的即时刷新测试使用（约定同 task-impl 的 SchedulerFakes）。
 */
package com.example.autop.feature.memory.testing

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.getOrNull
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryVersion
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 可换数据、可发树变更信号的 [MemoryStore] 假实现。 */
internal class FakeMemoryStore(
    var nodes: List<MemoryNode> = emptyList(),
    var recent: List<MemoryVersion> = emptyList(),
) : MemoryStore {
    private val treeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** 模拟外部写入（agent 写 / 删 / 移动 / 撤销）。 */
    fun emitTreeChange() {
        treeChanges.tryEmit(Unit)
    }

    override fun observeTree(): Flow<Unit> = treeChanges

    override suspend fun children(
        path: MemoryPath,
        offset: Int,
        limit: Int,
    ): List<MemoryNode> = nodes.drop(offset).take(limit)

    override suspend fun recentChanges(
        offset: Int,
        limit: Int,
        source: MemorySource?,
    ): List<MemoryVersion> = recent.drop(offset).take(limit)

    override suspend fun get(path: MemoryPath): MemoryNode? = error("unused")

    override suspend fun write(w: MemoryWrite): Outcome<WriteResult> = error("unused")

    override suspend fun delete(
        path: MemoryPath,
        recursive: Boolean,
        source: MemorySource,
        runId: RunId?,
    ): Outcome<Int> = error("unused")

    override suspend fun move(
        from: MemoryPath,
        to: MemoryPath,
    ): Outcome<Unit> = error("unused")

    override suspend fun versions(
        path: MemoryPath,
        limit: Int,
    ): List<MemoryVersion> = error("unused")

    override suspend fun revert(versionId: Long): Outcome<WriteResult> = error("unused")

    override suspend fun changesOfRun(runId: RunId): List<MemoryVersion> = error("unused")

    override suspend fun revertRun(runId: RunId): Outcome<Int> = error("unused")

    override suspend fun markConfirmed(path: MemoryPath): Unit = Unit
}

/** 便捷条目构造（content 空串即目录，与 [BrowseRow][com.example.autop.feature.memory.browse.BrowseRow] 口径一致）。 */
internal fun memoryNode(
    path: String,
    title: String,
    content: String = "正文",
): MemoryNode =
    MemoryNode(
        path = MemoryPath.parse(path).getOrNull() ?: error("测试路径非法：$path"),
        title = title,
        summary = "摘要",
        content = content,
        tags = emptyList(),
        links = emptyList(),
        source = MemorySource.USER,
        version = 1,
        createdAt = 0L,
        updatedAt = 0L,
        childCount = 0,
    )

/** 便捷历史版本构造。 */
internal fun memoryVersion(
    id: Long,
    title: String,
    createdAt: Long = id,
): MemoryVersion =
    MemoryVersion(
        id = id,
        path = MemoryPath.parse("/n$id").getOrNull() ?: error("unreachable"),
        version = 1,
        changeType = ChangeType.CREATE,
        titleBefore = null,
        titleAfter = title,
        contentBefore = null,
        contentAfter = "内容",
        source = MemorySource.AGENT,
        runId = null,
        reason = null,
        evidence = null,
        createdAt = createdAt,
    )
