/**
 * 线程（分支）与事件树存储实现（ADR-0021）。
 *
 * 事件全局唯一编号，分支只是指向头节点的指针；读历史即沿路径回溯，分叉不复制事件。
 */
package com.example.autop.agent.store

import androidx.room.withTransaction
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.store.db.ThreadEntity
import com.example.autop.agent.store.db.ThreadEventEntity
import com.example.autop.agent.store.db.ThreadsDb
import com.example.autop.agent.store.recall.IndexQueue
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 线程存储实现。 */
@Suppress("TooManyFunctions") // ThreadStore 接口的实现面，方法数即接口操作集
public class ThreadStoreImpl(
    private val db: ThreadsDb,
    private val mappers: ThreadMappers,
    private val indexQueue: IndexQueue,
    private val clock: Clock,
    private val ids: IdGenerator,
) : ThreadStore {
    private val eventFlow = MutableSharedFlow<ThreadEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * 建分支。[parent] 为分叉来源 `(分支, 分叉点 seq)`：新分支头节点即分叉点，标签与标题在事务内计算。
     * 顶层会话（无 parent）的 [ThreadEntity.rootId] 为自身。
     */
    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId {
        val id = ThreadId(ids.newId())
        val now = clock.nowMillis()
        db.withTransaction {
            val parentRow =
                parent?.let { (parentId, _) ->
                    db.threadDao().byId(parentId.value) ?: error("unknown parent thread '$parentId'")
                }
            val label = parentRow?.let { nextForkLabel(it) }
            val resolvedTitle = title ?: parentRow?.let { titleOfFork(it, label.orEmpty()) }
            db.threadDao().insert(
                ThreadEntity(
                    id = id.value,
                    title = resolvedTitle,
                    createdAt = now,
                    updatedAt = now,
                    parentThreadId = parent?.first?.value,
                    forkSeq = parent?.second,
                    forkLabel = label,
                    rootId = parentRow?.rootId ?: id.value,
                    headSeq = parent?.second,
                    archived = false,
                    lastRunStatus = null,
                    preview = null,
                    modelProfileId = null,
                ),
            )
        }
        changesFlow.tryEmit(Unit)
        return id
    }

    /** 下一个分叉标签：父标签加 `.n`（顶层分支的子分支为 `n`），n 为现有子分支最大序号加一。 */
    private suspend fun nextForkLabel(parent: ThreadEntity): String {
        val used = db.threadDao().childLabels(parent.id).mapNotNull { it?.substringAfterLast('.')?.toIntOrNull() }
        val index = (used.maxOrNull() ?: 0) + 1
        return parent.forkLabel?.let { "$it.$index" } ?: index.toString()
    }

    /** 分叉标题：根会话标题加标签，如 `买票(1.2)`。 */
    private suspend fun titleOfFork(
        parent: ThreadEntity,
        label: String,
    ): String {
        val root = db.threadDao().byId(parent.rootId)
        return "${root?.title.orEmpty()}($label)"
    }

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent {
        val encoded = PayloadCodec.encode(payload)
        val now = clock.nowMillis()
        val event =
            db.withTransaction {
                val thread =
                    db.threadDao().byId(threadId.value)
                        ?: error("unknown thread '$threadId'")
                val parent = thread.headSeq?.let { db.eventDao().bySeq(it) }
                val pending = nextPending(parent?.pending ?: 0, payload)
                val forkable = pending == 0 && (payload is AssistantMessage || payload is ToolResultEvent)
                val seq =
                    db.eventDao().insert(
                        ThreadEventEntity(
                            threadId = threadId.value,
                            parentSeq = thread.headSeq,
                            runId = runId?.value,
                            type = encoded.type,
                            v = encoded.v,
                            payload = "",
                            createdAt = now,
                            pending = pending,
                            forkable = forkable,
                        ),
                    )
                // 大 payload 的文件名依赖 seq，所以在插入拿到 seq 之后再写列值。
                db.eventDao().setPayload(seq, mappers.storePayload(threadId, seq, encoded.json))
                db.threadDao().updateOnAppend(
                    id = threadId.value,
                    headSeq = seq,
                    updatedAt = now,
                    preview = mappers.previewOf(payload),
                    lastRunStatus = mappers.runStatusOf(payload),
                )
                ThreadEvent(threadId, seq, runId, now, payload, forkable)
            }
        indexQueue.submit(event)
        eventFlow.emit(event)
        changesFlow.tryEmit(Unit)
        return event
    }

    /** 追加一个事件后路径上的未配对工具调用数：开启的调用加一，工具结果闭合一个。 */
    private fun nextPending(
        before: Int,
        payload: EventPayload,
    ): Int {
        val opened = (payload as? AssistantMessage)?.blocks?.count { it is Block.ToolCall } ?: 0
        val closed = if (payload is ToolResultEvent) 1 else 0
        return (before + opened - closed).coerceAtLeast(0)
    }

    /** 分支历史（路径）中 seq 在 [fromSeq]..[toSeq] 之间的事件。 */
    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> =
        db
            .eventDao()
            .path(threadId.value, fromSeq, toSeq)
            .map { mappers.toEvent(it) }

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> {
        val fromSeq = db.eventDao().lastOnPathOfType(threadId.value, BASELINE_TYPE) ?: 0
        return events(threadId, fromSeq, Long.MAX_VALUE)
    }

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = eventFlow.filter { it.threadId == threadId }

    /**
     * 订阅分支历史：先回放路径上 seq > [afterSeq] 的事件（一个列表），之后实时推送本分支新追加的事件。
     * UNDISPATCHED 先订阅热流再查库，查库期间到达的事件进 buffer，靠 `seq > last` 去重，不丢不重。
     * 祖先事件不会再变化，所以只需要实时推送本分支（归属为它）的新事件。
     */
    override fun observeFrom(
        threadId: ThreadId,
        afterSeq: Long,
    ): Flow<List<ThreadEvent>> =
        channelFlow {
            val buffer = Channel<ThreadEvent>(Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) {
                eventFlow.filter { it.threadId == threadId }.collect { buffer.send(it) }
            }
            val backlog = events(threadId, afterSeq + 1, Long.MAX_VALUE)
            var last = backlog.lastOrNull()?.seq ?: afterSeq
            send(backlog)
            buffer.consumeEach { event ->
                if (event.seq > last) {
                    send(listOf(event))
                    last = event.seq
                }
            }
        }

    override fun observeChanges(): Flow<Unit> = changesFlow.asSharedFlow()

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = mappers.saveBlob(threadId, name, bytes)

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> = db.threadDao().page(offset, limit).map { mappers.toSummary(it) }

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ) {
        db.threadDao().rename(threadId.value, title)
        changesFlow.tryEmit(Unit)
    }

    override suspend fun modelProfileOf(threadId: ThreadId): String? =
        db.threadDao().byId(threadId.value)?.modelProfileId

    override suspend fun setModelProfile(
        threadId: ThreadId,
        profileId: String?,
    ) {
        db.threadDao().setModelProfile(threadId.value, profileId)
        changesFlow.tryEmit(Unit)
    }

    override suspend fun titleOf(threadId: ThreadId): String? = db.threadDao().byId(threadId.value)?.title

    /**
     * 删除分支。删除行后，不再被任何剩余分支路径引用的事件一并删除（分叉共享的祖先事件保留）；
     * 归属分支已无事件的目录（大 payload 与附件）随之清理。
     */
    override suspend fun delete(threadId: ThreadId) {
        val removed =
            db.withTransaction {
                db.threadDao().delete(threadId.value)
                val unreachable = db.eventDao().unreachable()
                unreachable.map { it.seq }.chunked(DELETE_CHUNK).forEach { db.eventDao().deleteBySeqs(it) }
                unreachable
            }
        if (removed.isNotEmpty()) indexQueue.deleteEvents(removed.map { it.seq })
        val owners = (removed.map { it.threadId } + threadId.value).distinct()
        for (owner in owners) {
            if (db.eventDao().countOwned(owner) == 0) mappers.deleteThread(ThreadId(owner))
        }
        changesFlow.tryEmit(Unit)
    }

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = db.eventDao().forkableOnPath(threadId.value)

    private companion object {
        /** 观察流缓冲。 */
        const val EVENT_BUFFER = 256

        /** Baseline 事件的持久化类型（与 `@SerialName("baseline")` 一致，PayloadCodecTest 校验）。 */
        const val BASELINE_TYPE = "baseline"

        /** 删除事件时每批的数量，避免超过 SQLite 变量上限。 */
        const val DELETE_CHUNK = 500
    }
}
