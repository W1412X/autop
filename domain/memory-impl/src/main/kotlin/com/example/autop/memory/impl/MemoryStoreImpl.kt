/**
 * [MemoryStore] 实现（docs/tech/06 §2.4）：写入事务语义、版本记录、用户内容保护。
 *
 * 写入模式分派在 [MemoryWriter]；版本撤销在 [MemoryReverter]；实体互转在 `MemoryMappers.kt`。
 */
package com.example.autop.memory.impl

import androidx.room.withTransaction
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
import com.example.autop.memory.VectorStatus
import com.example.autop.memory.WriteResult
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity
import com.example.autop.memory.impl.db.MemoryVersionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /** 所有写操作串行执行（单用户应用，写入量小），保证事务之间不交错。 */
        private val writeLock = Mutex()

        /** 本次写操作中待索引的路径；只在 [writeLock] 内访问。 */
        private val indexPending = mutableListOf<String>()
        private val reverter = MemoryReverter(db, ::onMutated, ::enqueueIndex, clock)
        private val writer = MemoryWriter(db, prompts, configProvider, ::enqueueIndex, ::onMutated, clock)
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.default + logger.exceptionHandler("MemoryStore"))

        init {
            scope.launch { seeder.ensureRoots() }
        }

        override suspend fun get(path: MemoryPath): MemoryNode? {
            val entity = db.nodeDao().get(path.value) ?: return null
            return toModel(entity).copy(vectorStatus = indexer.statusOf(entity))
        }

        override suspend fun children(
            path: MemoryPath,
            offset: Int,
            limit: Int,
        ): List<MemoryNode> {
            val entities = db.nodeDao().children(path.value, offset, limit)
            val statuses = indexer.statusesOf(entities)
            return entities.map { toModel(it).copy(vectorStatus = statuses[it.path] ?: VectorStatus.DISABLED) }
        }

        /** 树变更（写入、删除、移动、撤销）与向量化完成都会发出，页面据此刷新。 */
        override fun observeTree(): Flow<Unit> = merge(treeChanges.asSharedFlow(), indexer.indexed)

        override suspend fun write(w: MemoryWrite): Outcome<WriteResult> = commit { writer.apply(w) }

        @Suppress("ReturnCount") // 出口按错误码分支（根保护/不存在/非空），是业务语义
        override suspend fun delete(
            path: MemoryPath,
            recursive: Boolean,
            source: MemorySource,
            runId: RunId?,
        ): Outcome<Int> = commit { deleteNodes(path, recursive, runId) }

        private suspend fun deleteNodes(
            path: MemoryPath,
            recursive: Boolean,
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
                enqueueIndex(p)
            }
            onMutated()
            return Outcome.Ok(targets.size)
        }

        @Suppress("ReturnCount") // 出口按错误码分支（根保护/不存在/已存在/缺父目录），是业务语义
        override suspend fun move(
            from: MemoryPath,
            to: MemoryPath,
        ): Outcome<Unit> = commit { moveNodes(from, to) }

        private suspend fun moveNodes(
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
                enqueueIndex(new)
            }
            recordMove(node, to)
            onMutated()
            return Outcome.Ok(Unit)
        }

        override suspend fun versions(
            path: MemoryPath,
            limit: Int,
        ): List<MemoryVersion> = db.versionDao().byPath(path.value, limit).map { toVersion(it) }

        override suspend fun revert(versionId: Long): Outcome<WriteResult> = commit { reverter.revert(versionId) }

        override suspend fun recentChanges(
            offset: Int,
            limit: Int,
            source: MemorySource?,
        ): List<MemoryVersion> = db.versionDao().recentBySource(offset, limit, source?.name).map { toVersion(it) }

        override suspend fun changesOfRun(runId: RunId): List<MemoryVersion> =
            db.versionDao().byRun(runId.value).map { toVersion(it) }

        override suspend fun revertRun(runId: RunId): Outcome<Int> = commit { reverter.revertRun(runId) }

        override suspend fun markConfirmed(path: MemoryPath) {
            commit { confirmNode(path) }
        }

        private suspend fun confirmNode(path: MemoryPath) {
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
                    keywordsBefore = node.keywordsJson,
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

        private fun enqueueIndex(path: String) {
            indexPending += path
        }

        /**
         * 一次写操作：串行化，整体放进一个数据库事务（读-校验-写节点-写版本作为一个原子单元）。
         * 向量索引在提交之后才入队，避免索引任务读到事务提交前的旧内容。
         */
        private suspend fun <T> commit(block: suspend () -> T): T =
            writeLock.withLock {
                indexPending.clear()
                val result = db.withTransaction { block() }
                indexPending.forEach(indexer::enqueue)
                indexPending.clear()
                result
            }

        private companion object {
            const val CHANGE_BUFFER: Int = 16
        }
    }
