/**
 * 写入执行（docs/tech/06 §2.4）：CREATE/UPDATE/APPEND 三模式与用户内容保护的 SUPPLEMENT 降级。
 *
 * 一个事务内完成：版本 +1、写 `memory_versions`、同步 `memory_fts`、path 入向量索引队列。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.memory.AppliedMode
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemoryErrorCodes
import com.example.autop.memory.MemoryEvidence
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.WriteResult
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity
import com.example.autop.memory.impl.db.MemoryVersionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 写入执行器。 */
@Suppress("LongParameterList") // 装配类：依赖都是写入事务的正交能力
internal class MemoryWriter(
    private val db: MemoryDb,
    private val fts: MemoryFtsSync,
    private val prompts: PromptRepository,
    private val configProvider: ConfigProvider,
    private val indexer: EmbeddingIndexer,
    private val onMutated: suspend () -> Unit,
    private val clock: Clock,
) {
    /** 按模式分派写入（docs/tech/06 §2.4）。 */
    suspend fun apply(w: MemoryWrite): Outcome<WriteResult> {
        val summary = configProvider.current()[MemoryConfig.SUMMARY]
        val maxContent = configProvider.current()[MemoryConfig.NODE].maxContentChars
        if (w.content.length > maxContent) {
            return Outcome.Err(AppError(MemoryErrorCodes.TOO_LARGE, "正文超过 $maxContent 字上限"))
        }
        val existing = db.nodeDao().get(w.path.value)
        return when {
            existing == null -> createNode(w, summary.maxChars)
            w.mode == WriteMode.CREATE ->
                Outcome.Err(AppError(MemoryErrorCodes.ALREADY_EXISTS, "路径已存在：${w.path.value}"))
            w.mode == WriteMode.UPDATE &&
                existing.source == MemorySource.USER.name &&
                w.source == MemorySource.AGENT -> supplementNode(w, existing)
            w.mode == WriteMode.APPEND -> appendNode(w, existing, summary.maxChars)
            else -> updateNode(w, existing, summary.maxChars)
        }
    }

    private suspend fun createNode(
        w: MemoryWrite,
        summaryMaxChars: Int,
    ): Outcome<WriteResult> {
        ensureParents(w.path, w.source)
        val now = clock.nowMillis()
        val node =
            MemoryNodeEntity(
                path = w.path.value,
                parentPath = w.path.parent?.value,
                title = w.title ?: w.path.name,
                summary = w.summary ?: MemoryText.summarize(w.content, summaryMaxChars),
                content = w.content,
                tagsJson = encodeList(w.tags.orEmpty()),
                linksJson = encodeList(w.links.orEmpty().map { it.value }),
                source = w.source.name,
                version = 1,
                createdAt = now,
                updatedAt = now,
                accessCount = 0,
                lastAccessAt = null,
            )
        db.nodeDao().insert(node)
        return finishWrite(w, node, AppliedMode.CREATED, beforeTitle = null, beforeContent = null)
    }

    private suspend fun updateNode(
        w: MemoryWrite,
        existing: MemoryNodeEntity,
        summaryMaxChars: Int,
    ): Outcome<WriteResult> {
        val updated =
            existing.copy(
                title = w.title ?: existing.title,
                summary = w.summary ?: MemoryText.summarize(w.content, summaryMaxChars),
                content = w.content,
                tagsJson = w.tags?.let { encodeList(it) } ?: existing.tagsJson,
                linksJson = w.links?.let { links -> encodeList(links.map { p -> p.value }) } ?: existing.linksJson,
                version = existing.version + 1,
                updatedAt = clock.nowMillis(),
            )
        db.nodeDao().update(updated)
        return finishWrite(
            w,
            updated,
            AppliedMode.UPDATED,
            beforeTitle = existing.title,
            beforeContent = existing.content,
        )
    }

    private suspend fun appendNode(
        w: MemoryWrite,
        existing: MemoryNodeEntity,
        summaryMaxChars: Int,
    ): Outcome<WriteResult> {
        val merged = existing.content + "\n\n" + w.content
        val appended =
            existing.copy(
                summary = w.summary ?: MemoryText.summarize(merged, summaryMaxChars),
                content = merged,
                version = existing.version + 1,
                updatedAt = clock.nowMillis(),
            )
        db.nodeDao().update(appended)
        return finishWrite(
            w,
            appended,
            AppliedMode.APPENDED,
            beforeTitle = existing.title,
            beforeContent = existing.content,
        )
    }

    /** 用户内容保护：agent 的 UPDATE 降级为补充段落，title/summary 不变。 */
    private suspend fun supplementNode(
        w: MemoryWrite,
        existing: MemoryNodeEntity,
    ): Outcome<WriteResult> {
        val supplement = renderSupplement(w)
        val merged = existing.content + supplement
        val supplemented =
            existing.copy(
                content = merged,
                version = existing.version + 1,
                updatedAt = clock.nowMillis(),
            )
        db.nodeDao().update(supplemented)
        return finishWrite(
            w,
            supplemented,
            AppliedMode.APPENDED_AS_SUPPLEMENT,
            beforeTitle = existing.title,
            beforeContent = existing.content,
        )
    }

    private fun renderSupplement(w: MemoryWrite): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(clock.nowMillis()))
        val rendered =
            prompts.render(
                id = configProvider.current()[MemoryConfig.WRITE].supplementPromptId,
                vars = mapOf("date" to date, "content" to w.content),
            )
        return when (rendered) {
            is Outcome.Ok -> "\n\n" + rendered.value.text
            is Outcome.Err -> "\n\n" + w.content
        }
    }

    private suspend fun finishWrite(
        w: MemoryWrite,
        node: MemoryNodeEntity,
        applied: AppliedMode,
        beforeTitle: String?,
        beforeContent: String?,
    ): Outcome<WriteResult> {
        val changeType =
            when (applied) {
                AppliedMode.CREATED -> ChangeType.CREATE
                AppliedMode.UPDATED -> ChangeType.UPDATE
                AppliedMode.APPENDED -> ChangeType.APPEND
                AppliedMode.APPENDED_AS_SUPPLEMENT -> ChangeType.SUPPLEMENT
            }
        val versionId =
            newVersion(
                node = node,
                changeType = changeType,
                titleBefore = beforeTitle,
                contentBefore = beforeContent,
                runId = w.runId,
                reason = w.reason,
                evidence = w.evidence,
            ).let { db.versionDao().insert(it) }
        fts.syncNode(node)
        indexer.enqueue(node.path)
        onMutated()
        return Outcome.Ok(WriteResult(node = toModel(node), versionId = versionId, appliedMode = applied))
    }

    /** 父目录不存在时逐级自动创建空目录节点（source 同写入者）。 */
    private suspend fun ensureParents(
        path: MemoryPath,
        source: MemorySource,
    ) {
        val segments = path.segments()
        if (segments.isEmpty()) return
        var current = MemoryPath.ROOT
        for (seg in segments.dropLast(1)) {
            current = current.child(seg)
            if (db.nodeDao().get(current.value) == null) {
                val now = clock.nowMillis()
                db.nodeDao().insertIfAbsent(
                    MemoryNodeEntity(
                        path = current.value,
                        parentPath = current.parent?.value,
                        title = seg,
                        summary = "",
                        content = "",
                        tagsJson = "[]",
                        linksJson = "[]",
                        source = source.name,
                        version = 1,
                        createdAt = now,
                        updatedAt = now,
                        accessCount = 0,
                        lastAccessAt = null,
                    ),
                )
            }
        }
    }

    @Suppress("LongParameterList") // 字段面就是版本实体的构成
    private fun newVersion(
        node: MemoryNodeEntity,
        changeType: ChangeType,
        titleBefore: String?,
        contentBefore: String?,
        runId: RunId?,
        reason: String?,
        evidence: MemoryEvidence?,
    ): MemoryVersionEntity =
        MemoryVersionEntity(
            path = node.path,
            version = node.version,
            changeType = changeType.name,
            titleBefore = titleBefore,
            titleAfter = node.title,
            contentBefore = contentBefore,
            contentAfter = node.content,
            source = node.source,
            runId = runId?.value,
            reason = reason,
            evidenceJson = evidence?.let { encodeEvidence(it) },
            createdAt = clock.nowMillis(),
        )
}
