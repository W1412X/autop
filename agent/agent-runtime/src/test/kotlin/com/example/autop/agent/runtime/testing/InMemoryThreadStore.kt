/**
 * 内存版 [ThreadStore]（docs/tech/04 §6 测试基建）：事件按线程存内存、seq 自增，
 * 可分叉点按 docs/tech/04 §3.11 计算（ASSISTANT/TOOL 批次结束且无未配对 ToolCall）。
 */
package com.example.autop.agent.runtime.testing

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunFinished
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.core.common.Clock
import com.example.autop.core.common.RunId
import com.example.autop.core.common.SystemClock
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 内存线程存储。 */
internal class InMemoryThreadStore(
    private val clock: Clock = SystemClock(),
) : ThreadStore {
    private class ThreadRow(
        val id: ThreadId,
        val title: String?,
        val parent: Pair<ThreadId, Long>?,
        val createdAt: Long,
    ) {
        var updatedAt: Long = createdAt
        var name: String? = title
        val events: MutableList<ThreadEvent> = mutableListOf()
    }

    private val threads = linkedMapOf<String, ThreadRow>()
    private val blobs = linkedMapOf<String, ByteArray>()
    private val eventFlow = MutableSharedFlow<ThreadEvent>(extraBufferCapacity = 256)
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 256)
    private var nextThread = 1
    private var nextBlob = 1

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId {
        val id = ThreadId("t${nextThread++}")
        val now = clock.nowMillis()
        threads[id.value] = ThreadRow(id, title, parent, now)
        changesFlow.tryEmit(Unit)
        return id
    }

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent {
        val row = row(threadId)
        val event = ThreadEvent(threadId, row.events.size + 1L, runId, clock.nowMillis(), payload)
        row.events += event
        row.updatedAt = event.createdAt
        eventFlow.emit(event)
        changesFlow.tryEmit(Unit)
        return event
    }

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = row(threadId).events.filter { it.seq in fromSeq..toSeq }

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> {
        val all = events(threadId, 0, Long.MAX_VALUE)
        val idx = all.indexOfLast { it.payload is Baseline }
        return if (idx < 0) all else all.drop(idx)
    }

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = eventFlow.filter { it.threadId == threadId }

    /** 同 ThreadStoreImpl：先回放后实时（WP8.3），UNDISPATCHED 先订阅再去重。 */
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
    ): String {
        val ref = "blob:${nextBlob++}:$name"
        blobs[ref] = bytes
        return ref
    }

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> =
        threads.values
            .sortedByDescending { it.updatedAt }
            .drop(offset)
            .take(limit)
            .map { row -> row.toSummary() }

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ) {
        row(threadId).name = title
        changesFlow.tryEmit(Unit)
    }

    override suspend fun titleOf(threadId: ThreadId): String? = threads[threadId.value]?.name

    override suspend fun delete(threadId: ThreadId) {
        threads.remove(threadId.value)
        changesFlow.tryEmit(Unit)
    }

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> {
        val events = events(threadId, 0, Long.MAX_VALUE)
        val open = mutableSetOf<String>()
        val points = mutableListOf<Long>()
        events.forEachIndexed { i, event ->
            when (val payload = event.payload) {
                is AssistantMessage ->
                    payload.blocks.filterIsInstance<Block.ToolCall>().forEach { open += it.id }
                is ToolResultEvent -> open -= payload.callId
                else -> Unit
            }
            if (event.payload is AssistantMessage || event.payload is ToolResultEvent) {
                val next = events.getOrNull(i + 1)?.payload
                val batchEnd = next !is AssistantMessage && next !is ToolResultEvent
                if (batchEnd && open.isEmpty()) points += event.seq
            }
        }
        return points
    }

    /** 测试断言入口：线程内某类型的全部载荷。 */
    fun payloadsOf(threadId: ThreadId): List<EventPayload> = row(threadId).events.map { it.payload }

    private fun row(threadId: ThreadId): ThreadRow = threads[threadId.value] ?: error("unknown thread '$threadId'")

    private fun ThreadRow.toSummary(): ThreadSummary {
        val lastUser = events.lastOrNull { it.payload is UserMessage }
        val preview = (lastUser?.payload as? UserMessage)?.blocks?.firstOrNull() as? Block.Text
        return ThreadSummary(
            threadId = id,
            title = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            preview = preview?.text?.take(60),
            lastRunStatus = (events.lastOrNull { it.payload is RunFinished }?.payload as? RunFinished)?.status,
        )
    }
}
