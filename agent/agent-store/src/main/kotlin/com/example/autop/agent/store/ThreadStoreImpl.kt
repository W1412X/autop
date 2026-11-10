/**
 * [ThreadStore] 的 Room 实现（docs/tech/04 §4）：append 在一个事务里分配 seq 并
 * 更新线程行，提交后把事件交给 [IndexQueue] 建立检索索引。
 */
package com.example.autop.agent.store

import androidx.room.withTransaction
import com.example.autop.agent.Baseline
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.store.db.LatestBaselineEntity
import com.example.autop.agent.store.db.ThreadEntity
import com.example.autop.agent.store.db.ThreadEventEntity
import com.example.autop.agent.store.db.ThreadsDb
import com.example.autop.agent.store.recall.IndexQueue
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
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

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId {
        val id = ThreadId(ids.newId())
        val now = clock.nowMillis()
        db.threadDao().insert(
            ThreadEntity(
                id = id.value,
                title = title,
                createdAt = now,
                updatedAt = now,
                parentThreadId = parent?.first?.value,
                parentSeq = parent?.second,
                lastSeq = 0,
                archived = false,
                lastRunStatus = null,
                preview = null,
            ),
        )
        changesFlow.tryEmit(Unit)
        return id
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
                val seq = thread.lastSeq + 1
                val payloadCol = mappers.storePayload(threadId, seq, encoded.json)
                db.eventDao().insert(
                    ThreadEventEntity(
                        threadId = threadId.value,
                        seq = seq,
                        runId = runId?.value,
                        type = encoded.type,
                        v = encoded.v,
                        payload = payloadCol,
                        createdAt = now,
                    ),
                )
                db.threadDao().updateOnAppend(
                    id = threadId.value,
                    lastSeq = seq,
                    updatedAt = now,
                    preview = mappers.previewOf(payload),
                    lastRunStatus = mappers.runStatusOf(payload),
                )
                if (payload is Baseline) {
                    db.baselineDao().upsert(LatestBaselineEntity(threadId.value, seq))
                }
                ThreadEvent(threadId, seq, runId, now, payload)
            }
        indexQueue.submit(event)
        eventFlow.emit(event)
        changesFlow.tryEmit(Unit)
        return event
    }

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> =
        db
            .eventDao()
            .range(threadId.value, fromSeq, toSeq)
            .map { mappers.toEvent(it) }

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> {
        val fromSeq = db.baselineDao().latestSeq(threadId.value) ?: 0
        return events(threadId, fromSeq, Long.MAX_VALUE)
    }

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = eventFlow.filter { it.threadId == threadId }

    /**
     * 订阅线程事件（WP8.3）：先回放 seq > [afterSeq] 的已有事件（一个列表），之后每条新事件
     * 发一个单元素列表。UNDISPATCHED 先订阅热流再查库，查库期间到达的事件进 buffer，
     * 靠 `seq > last` 去重，不丢不重。
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
            for (event in buffer) {
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

    override suspend fun titleOf(threadId: ThreadId): String? = db.threadDao().byId(threadId.value)?.title

    override suspend fun delete(threadId: ThreadId) {
        db.withTransaction {
            db.eventDao().deleteThread(threadId.value)
            db.baselineDao().deleteThread(threadId.value)
            db.threadDao().delete(threadId.value)
        }
        indexQueue.deleteThread(threadId)
        mappers.deleteThread(threadId)
        changesFlow.tryEmit(Unit)
    }

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> =
        mappers.forkablePoints(events(threadId, 0, Long.MAX_VALUE))

    private companion object {
        /** 观察流缓冲。 */
        const val EVENT_BUFFER = 256
    }
}
