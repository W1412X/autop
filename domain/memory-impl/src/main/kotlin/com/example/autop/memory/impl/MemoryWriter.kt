/**
 * 写入执行（docs/tech/06 §2.4，ADR-0027/0028）：CREATE/UPDATE/APPEND 三模式与用户内容保护的
 * SUPPLEMENT 降级；字段校验（关键词、摘要）；agent 新建的同名检查。
 *
 * 事务与写锁由 [MemoryStoreImpl] 包裹，本类只负责单次写入的业务规则。
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
import com.example.autop.memory.isAutomated
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 写入执行器。 */
@Suppress("LongParameterList") // 装配类：依赖都是写入规则的正交能力
internal class MemoryWriter(
    private val db: MemoryDb,
    private val prompts: PromptRepository,
    private val configProvider: ConfigProvider,
    /** 节点内容或关键词变化后登记向量索引待更新的路径（提交后执行）。 */
    private val onIndexed: (String) -> Unit,
    private val onMutated: suspend () -> Unit,
    private val clock: Clock,
) {
    /** 按模式分派写入（docs/tech/06 §2.4）。 */
    suspend fun apply(input: MemoryWrite): Outcome<WriteResult> {
        val cfg = configProvider.current()
        val maxContent = cfg[MemoryConfig.NODE].maxContentChars
        if (input.content.length > maxContent) {
            return Outcome.Err(AppError(MemoryErrorCodes.TOO_LARGE, "正文超过 $maxContent 字上限"))
        }
        val w =
            when (val checked = normalizeFields(input)) {
                is Outcome.Err -> return checked
                is Outcome.Ok -> checked.value
            }
        val existing = db.nodeDao().get(w.path.value)
        return when {
            existing == null -> createNode(w)
            w.mode == WriteMode.CREATE ->
                Outcome.Err(AppError(MemoryErrorCodes.ALREADY_EXISTS, "路径已存在：${w.path.value}"))
            w.mode == WriteMode.UPDATE &&
                existing.source == MemorySource.USER.name &&
                w.source.isAutomated -> supplementNode(w, existing)
            w.mode == WriteMode.APPEND -> appendNode(w, existing)
            else -> updateNode(w, existing)
        }
    }

    /** 规范化关键词并校验长度、数量与摘要长度。返回规范化后的写入请求。 */
    private fun normalizeFields(input: MemoryWrite): Outcome<MemoryWrite> {
        val cfg = configProvider.current()
        val keywords = input.keywords?.let(MemoryKeywords::normalizeList)
        keywords?.let { list ->
            val section = cfg[MemoryConfig.KEYWORDS]
            MemoryKeywords.validate(list, section.maxCount, section.maxChars)?.let { return invalid(it) }
        }
        val maxSummary = cfg[MemoryConfig.SUMMARY].maxChars
        input.summary?.let { if (it.length > maxSummary) return invalid("摘要最多 $maxSummary 字") }
        return Outcome.Ok(input.copy(keywords = keywords))
    }

    private suspend fun createNode(w: MemoryWrite): Outcome<WriteResult> {
        if (w.source == MemorySource.AGENT) {
            missingFieldsError(w)?.let { return Outcome.Err(it) }
            if (!w.forceNew) duplicateError(w)?.let { return Outcome.Err(it) }
        }
        ensureParents(w.path, w.source)
        val now = clock.nowMillis()
        val maxSummary = configProvider.current()[MemoryConfig.SUMMARY].maxChars
        val node =
            MemoryNodeEntity(
                path = w.path.value,
                parentPath = w.path.parent?.value,
                title = w.title ?: w.path.name,
                summary = w.summary ?: MemoryText.summarize(w.content, maxSummary),
                content = w.content,
                keywordsJson = encodeList(w.keywords.orEmpty()),
                linksJson = encodeList(w.links.orEmpty().map { it.value }),
                source = w.source.name,
                version = 1,
                createdAt = now,
                updatedAt = now,
                accessCount = 0,
                lastAccessAt = null,
            )
        db.nodeDao().insert(node)
        return finishWrite(
            w,
            node,
            AppliedMode.CREATED,
            beforeTitle = null,
            beforeContent = null,
            beforeKeywords = null,
        )
    }

    /** agent 新建必须有摘要（用于注入与列表）和至少一个关键词（用于检索）。 */
    private fun missingFieldsError(w: MemoryWrite): AppError? {
        val message =
            when {
                w.summary.isNullOrBlank() -> "新建条目需要摘要（summary），用一句话说明它是什么"
                w.keywords.isNullOrEmpty() -> "新建条目需要至少一个关键词（keywords），写用户以后可能提到的名字或说法"
                else -> return null
            }
        return AppError(code = MemoryErrorCodes.INVALID_FIELDS, message = message)
    }

    /**
     * agent 新建时，同一父目录下已有同名条目（标题忽略大小写与首尾空白），或关键词集合完全相同，
     * 则拒绝，避免同一对象被建成多条。确实是另一个对象时由调用方传 `forceNew`。
     */
    private suspend fun duplicateError(w: MemoryWrite): AppError? {
        val parent = w.path.parent?.value ?: return null
        val title = MemoryKeywords.normalize(w.title ?: w.path.name)
        val keywords = w.keywords.orEmpty().toSet()
        val sibling =
            db.nodeDao().childrenAll(parent).firstOrNull { row ->
                MemoryKeywords.normalize(row.title) == title ||
                    (keywords.isNotEmpty() && decodeList(row.keywordsJson).toSet() == keywords)
            } ?: return null
        return AppError(
            code = MemoryErrorCodes.POSSIBLE_DUPLICATE,
            message = "同一目录下已有相同的条目：${sibling.path}",
            hint = "请改用 UPDATE 更新 ${sibling.path}；确实是另一个对象时，传 force_new=true 再写入",
        )
    }

    private suspend fun updateNode(
        w: MemoryWrite,
        existing: MemoryNodeEntity,
    ): Outcome<WriteResult> {
        val updated =
            existing.copy(
                title = w.title ?: existing.title,
                summary = w.summary ?: existing.summary,
                content = w.content,
                keywordsJson = w.keywords?.let { encodeList(it) } ?: existing.keywordsJson,
                linksJson = w.links?.let { links -> encodeList(links.map { it.value }) } ?: existing.linksJson,
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
            beforeKeywords = existing.keywordsJson,
        )
    }

    private suspend fun appendNode(
        w: MemoryWrite,
        existing: MemoryNodeEntity,
    ): Outcome<WriteResult> {
        val merged = existing.content + "\n\n" + w.content
        val appended =
            existing.copy(
                summary = w.summary ?: existing.summary,
                content = merged,
                keywordsJson = w.keywords?.let { encodeList(it) } ?: existing.keywordsJson,
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
            beforeKeywords = existing.keywordsJson,
        )
    }

    /** 用户内容保护：agent 的 UPDATE 降级为补充段落，title、摘要与关键词不变。 */
    private suspend fun supplementNode(
        w: MemoryWrite,
        existing: MemoryNodeEntity,
    ): Outcome<WriteResult> {
        val merged = existing.content + renderSupplement(w)
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
            beforeKeywords = existing.keywordsJson,
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
        beforeKeywords: String?,
    ): Outcome<WriteResult> {
        val changeType =
            when (applied) {
                AppliedMode.CREATED -> ChangeType.CREATE
                AppliedMode.UPDATED -> ChangeType.UPDATE
                AppliedMode.APPENDED -> ChangeType.APPEND
                AppliedMode.APPENDED_AS_SUPPLEMENT -> ChangeType.SUPPLEMENT
            }
        val versionId =
            db.versionDao().insert(
                newVersion(
                    node = node,
                    changeType = changeType,
                    titleBefore = beforeTitle,
                    contentBefore = beforeContent,
                    keywordsBefore = beforeKeywords,
                    runId = w.runId,
                    reason = w.reason,
                    evidence = w.evidence,
                ),
            )
        onIndexed(node.path)
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
                        keywordsJson = "[]",
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
        keywordsBefore: String?,
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
            keywordsBefore = keywordsBefore,
            source = node.source,
            runId = runId?.value,
            reason = reason,
            evidenceJson = evidence?.let { encodeEvidence(it) },
            createdAt = clock.nowMillis(),
        )

    private fun invalid(message: String): Outcome.Err = Outcome.Err(AppError(MemoryErrorCodes.INVALID_FIELDS, message))
}
