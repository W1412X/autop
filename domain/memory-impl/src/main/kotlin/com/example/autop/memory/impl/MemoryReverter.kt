/**
 * 版本撤销（docs/tech/06 §1/§2.4）：按 [MemoryVersion] 的 before 值恢复节点（标题、正文、关键词），
 * 记 REVERT 版本；按 Run 撤销 = 该 Run 的变更按时间倒序逐条撤销。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.memory.AppliedMode
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemoryErrorCodes
import com.example.autop.memory.MemorySource
import com.example.autop.memory.WriteResult
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity
import com.example.autop.memory.impl.db.MemoryVersionEntity

/** 版本撤销执行器。 */
internal class MemoryReverter(
    private val db: MemoryDb,
    private val onMutated: suspend () -> Unit,
    /** 节点内容、关键词或存在性变化后登记向量索引待更新的路径（提交后执行）。 */
    private val onIndexed: (String) -> Unit,
    private val clock: Clock,
) {
    /** 撤销一次变更（CREATE 撤销 = 删除节点，DELETE 撤销 = 恢复节点）。 */
    suspend fun revert(versionId: Long): Outcome<WriteResult> {
        val version =
            db.versionDao().byId(versionId)
                ?: return Outcome.Err(AppError(MemoryErrorCodes.NOT_FOUND, "版本不存在：$versionId"))
        val before = db.nodeDao().get(version.path)
        val attempted =
            when {
                version.changeType == ChangeType.CREATE.name -> revertCreate(version, before)
                version.changeType == ChangeType.DELETE.name -> revertDelete(version, before)
                before == null -> Outcome.Err(AppError(MemoryErrorCodes.NOT_FOUND, "节点不存在：${version.path}"))
                else -> restoreFields(version, before)
            }
        return when (attempted) {
            is Outcome.Err -> attempted
            is Outcome.Ok -> {
                val (restored, applied) = attempted.value
                val revertVersionId = recordVersion(version, before, restored)
                Outcome.Ok(WriteResult(node = toModel(restored), versionId = revertVersionId, appliedMode = applied))
            }
        }
    }

    /** 按 Run 撤销：变更按时间倒序逐条撤销，返回撤销条数。 */
    suspend fun revertRun(runId: RunId): Outcome<Int> {
        val versions = db.versionDao().byRun(runId.value)
        var count = 0
        for (v in versions) {
            when (val r = revert(v.id)) {
                is Outcome.Ok -> count++
                is Outcome.Err -> return r
            }
        }
        return Outcome.Ok(count)
    }

    private suspend fun revertCreate(
        version: MemoryVersionEntity,
        node: MemoryNodeEntity?,
    ): Outcome<Pair<MemoryNodeEntity, AppliedMode>> {
        if (node == null) {
            return Outcome.Err(AppError(MemoryErrorCodes.NOT_FOUND, "节点不存在：${version.path}"))
        }
        db.nodeDao().delete(node.path)
        onIndexed(node.path)
        onMutated()
        // 删除后的占位：调用方仍需要 node 描述变更，这里原样返回被删节点
        return Outcome.Ok(node to AppliedMode.UPDATED)
    }

    private suspend fun revertDelete(
        version: MemoryVersionEntity,
        node: MemoryNodeEntity?,
    ): Outcome<Pair<MemoryNodeEntity, AppliedMode>> {
        if (node != null) {
            return Outcome.Err(AppError(MemoryErrorCodes.ALREADY_EXISTS, "节点已存在，无需恢复：${version.path}"))
        }
        val restored = newNodeForRestore(version)
        db.nodeDao().insert(restored)
        onIndexed(restored.path)
        onMutated()
        return Outcome.Ok(restored to AppliedMode.CREATED)
    }

    private suspend fun restoreFields(
        version: MemoryVersionEntity,
        node: MemoryNodeEntity,
    ): Outcome<Pair<MemoryNodeEntity, AppliedMode>> {
        val restored =
            node.copy(
                title = version.titleBefore ?: node.title,
                content = version.contentBefore ?: node.content,
                keywordsJson = version.keywordsBefore ?: node.keywordsJson,
                version = node.version + 1,
                updatedAt = clock.nowMillis(),
            )
        db.nodeDao().update(restored)
        onIndexed(restored.path)
        onMutated()
        return Outcome.Ok(restored to AppliedMode.UPDATED)
    }

    private fun newNodeForRestore(version: MemoryVersionEntity): MemoryNodeEntity =
        MemoryNodeEntity(
            path = version.path,
            parentPath = parentPathOf(version.path),
            title = version.titleBefore ?: version.path.substringAfterLast('/'),
            summary = MemoryText.summarize(version.contentBefore.orEmpty(), DEFAULT_SUMMARY_CHARS),
            content = version.contentBefore.orEmpty(),
            keywordsJson = version.keywordsBefore ?: "[]",
            linksJson = "[]",
            source = version.source,
            version = version.version + 1,
            createdAt = clock.nowMillis(),
            updatedAt = clock.nowMillis(),
            accessCount = 0,
            lastAccessAt = null,
        )

    /** 记录撤销本身：变更前的状态取撤销前的节点（可能已被删除），变更后取撤销后的节点。 */
    private suspend fun recordVersion(
        reverted: MemoryVersionEntity,
        before: MemoryNodeEntity?,
        after: MemoryNodeEntity,
    ): Long {
        val isCreateRevert = reverted.changeType == ChangeType.CREATE.name
        return db.versionDao().insert(
            MemoryVersionEntity(
                path = after.path,
                version = after.version,
                changeType = ChangeType.REVERT.name,
                titleBefore = before?.title,
                titleAfter = if (isCreateRevert) null else after.title,
                contentBefore = before?.content,
                contentAfter = if (isCreateRevert) null else after.content,
                keywordsBefore = before?.keywordsJson,
                source = MemorySource.USER.name,
                runId = null,
                reason = "revert#${reverted.id}",
                evidenceJson = null,
                createdAt = clock.nowMillis(),
            ),
        )
    }

    private companion object {
        /** 恢复删除节点时摘要截取长度，与 `memory.summary.maxChars` 默认值一致。 */
        const val DEFAULT_SUMMARY_CHARS: Int = 60
    }
}
