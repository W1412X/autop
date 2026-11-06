/**
 * [MemoryStore] 实现（docs/tech/06 §2.4）：写入事务语义、版本记录、用户内容保护。
 *
 * 写入模式分派在 [MemoryWriter]；版本撤销在 [MemoryReverter]；实体互转在 `MemoryMappers.kt`。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemoryErrorCodes
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryVersion
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteResult
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity
import com.example.autop.memory.impl.db.MemoryVersionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 记忆存储实现。 */
@Singleton
@Suppress("TooManyFunctions", "LongParameterList")
public class MemoryStoreImpl
    @Inject
    constructor(
        private val db: MemoryDb,
        prompts: PromptRepository,
        configProvider: ConfigProvider,
        private val indexer: EmbeddingIndexer,
        seeder: MemorySeeder,
        dispatchers: DispatcherProvider,
        private val clock: Clock,
        private val logger: Logger,
    ) : MemoryStore {
        private val treeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = CHANGE_BUFFER)
        private val fts = MemoryFtsSync(db)
        private val reverter = MemoryReverter(db, fts, ::onMutated, clock)
        private val writer =
            MemoryWriter(db, fts, prompts, configProvider, indexer, ::onMutated, clock)
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.default + logger.exceptionHandler("MemoryStore"))

        init {
            scope.launch { seeder.ensureRoots() }
        }

        override suspend fun get(path: MemoryPath): MemoryNode? = db.nodeDao().get(path.value)?.let { toModel(it) }

        override suspend fun children(
            path: MemoryPath,
            offset: Int,
            limit: Int,
        ): List<MemoryNode> = db.nodeDao().children(path.value, offset, limit).map { toModel(it) }

        override fun observeTree(): Flow<Unit> = treeChanges.asSharedFlow()

        override suspend fun write(w: MemoryWrite): Outcome<WriteResult> = writer.apply(w)

        @Suppress("ReturnCount") // 出口按错误码分支（根保护/不存在/非空），是业务语义
        override suspend fun delete(
            path: MemoryPath,
            recursive: Boolean,
            source: MemorySource,
            runId: RunId?,
        ): Outcome<Int> {
            if (path.isProtectedRoot()) {
                return Outcome.Err(AppError(MemoryErrorCodes.ROOT_PROTECTED, "内置根目录不能删除：${path.value}"))
            }
            val node =
                db.nodeDao().get(path.value)
                    ?: return Outcome.Err(AppError(MemoryErrorCodes.NOT_FOUND, "路径不存在：${path.value}"))
            val childTotal = db.nodeDao().childCount(node.path)
            if (childTotal > 0 && !recursive) {
                return Outcome.Err(AppError(MemoryErrorCodes.ALREADY_EXISTS, "目录非空，请使用递归删除"))
            }
            val targets = db.nodeDao().pathsUnder(node.path).sortedBy { it.length }
            targets.forEach { p ->
                db.nodeDao().get(p)?.let { recordDelete(it, runId) }
                db.nodeDao().delete(p)
                fts.remove(p)
                indexer.enqueue(p)
            }
            onMutated()
            return Outcome.Ok(targets.size)
        }

        @Suppress("ReturnCount") // 出口按错误码分支（根保护/不存在/已存在/缺父目录），是业务语义
        override suspend fun move(
            from: MemoryPath,
            to: MemoryPath,
        ): Outcome<Unit> {
            if (from.isProtectedRoot() || to.isProtectedRoot()) {
                return Outcome.Err(AppError(MemoryErrorCodes.ROOT_PROTECTED, "内置根目录不能移动"))
            }
            val node =
                db.nodeDao().get(from.value)
                    ?: return Outcome.Err(AppError(MemoryErrorCodes.NOT_FOUND, "路径不存在：${from.value}"))
            if (db.nodeDao().get(to.value) != null) {
                return Outcome.Err(AppError(MemoryErrorCodes.ALREADY_EXISTS, "目标路径已存在：${to.value}"))
            }
            val toParent =
                to.parent?.value
                    ?: return Outcome.Err(AppError(MemoryErrorCodes.INVALID_PATH, "目标缺少父路径"))
            if (db.nodeDao().get(toParent) == null) {
                return Outcome.Err(AppError(MemoryErrorCodes.NOT_FOUND, "目标父目录不存在：$toParent"))
            }
            val targets = db.nodeDao().pathsUnder(node.path).sortedBy { it.length }
            targets.forEach { old ->
                val new = to.value + old.removePrefix(node.path)
                db.nodeDao().moveNode(oldPath = old, newPath = new, newParent = parentPathOf(new))
                fts.remove(old)
                db.nodeDao().get(new)?.let { fts.syncNode(it) }
                indexer.enqueue(new)
            }
            recordMove(node, to)
            onMutated()
            return Outcome.Ok(Unit)
        }

        override suspend fun versions(
            path: MemoryPath,
            limit: Int,
        ): List<MemoryVersion> = db.versionDao().byPath(path.value, limit).map { toVersion(it) }

        override suspend fun revert(versionId: Long): Outcome<WriteResult> = reverter.revert(versionId)

        override suspend fun recentChanges(
            offset: Int,
            limit: Int,
            source: MemorySource?,
        ): List<MemoryVersion> = db.versionDao().recentBySource(offset, limit, source?.name).map { toVersion(it) }

        override suspend fun changesOfRun(runId: RunId): List<MemoryVersion> =
            db.versionDao().byRun(runId.value).map { toVersion(it) }

        override suspend fun revertRun(runId: RunId): Outcome<Int> = reverter.revertRun(runId)

        override suspend fun markConfirmed(path: MemoryPath) {
            val node = db.nodeDao().get(path.value) ?: return
            if (node.source != MemorySource.AGENT.name && node.source != MemorySource.FLOW.name) return
            val confirmed = node.copy(source = MemorySource.USER.name, updatedAt = clock.nowMillis())
            db.nodeDao().update(confirmed)
            db.versionDao().insert(
                MemoryVersionEntity(
                    path = node.path,
                    version = node.version,
                    changeType = ChangeType.CONFIRM.name,
                    titleBefore = node.title,
                    titleAfter = node.title,
                    contentBefore = node.content,
                    contentAfter = node.content,
                    source = MemorySource.USER.name,
                    runId = null,
                    reason = null,
                    evidenceJson = null,
                    createdAt = clock.nowMillis(),
                ),
            )
            onMutated()
        }

        private suspend fun recordDelete(
            node: MemoryNodeEntity,
            runId: RunId?,
        ) {
            db.versionDao().insert(
                MemoryVersionEntity(
                    path = node.path,
                    version = node.version,
                    changeType = ChangeType.DELETE.name,
                    titleBefore = node.title,
                    titleAfter = null,
                    contentBefore = node.content,
                    contentAfter = null,
                    source = node.source,
                    runId = runId?.value,
                    reason = null,
                    evidenceJson = null,
                    createdAt = clock.nowMillis(),
                ),
            )
        }

        private suspend fun recordMove(
            node: MemoryNodeEntity,
            to: MemoryPath,
        ) {
            db.versionDao().insert(
                MemoryVersionEntity(
                    path = node.path,
                    version = node.version,
                    changeType = ChangeType.MOVE.name,
                    titleBefore = node.path,
                    titleAfter = to.value,
                    contentBefore = null,
                    contentAfter = null,
                    source = node.source,
                    runId = null,
                    reason = to.value,
                    evidenceJson = null,
                    createdAt = clock.nowMillis(),
                ),
            )
        }

        private suspend fun onMutated() {
            treeChanges.tryEmit(Unit)
        }

        private companion object {
            const val CHANGE_BUFFER: Int = 16
        }
    }
