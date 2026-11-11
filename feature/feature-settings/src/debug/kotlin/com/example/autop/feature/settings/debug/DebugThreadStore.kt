/**
 * 调试会话的内存版线程存储（docs/tech/10 WP4）：
 * 事件只进内存；`saveBlob` 把二进制附件写到 `cacheDir/debug/`（截图等）。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.agent.Baseline
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

internal class DebugThreadStore(
    private val blobDir: File,
) : ThreadStore {
    private val events = mutableListOf<ThreadEvent>()
    private val push = MutableSharedFlow<ThreadEvent>(extraBufferCapacity = PUSH_BUFFER)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = PUSH_BUFFER)

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId {
        changes.tryEmit(Unit)
        return ThreadId(DEBUG_THREAD_ID)
    }

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent {
        val event =
            synchronized(events) {
                val seq = (events.lastOrNull()?.seq ?: 0L) + 1
                ThreadEvent(
                    threadId = threadId,
                    seq = seq,
                    runId = runId,
                    createdAt = System.currentTimeMillis(),
                    payload = payload,
                ).also { events += it }
            }
        push.emit(event)
        changes.tryEmit(Unit)
        return event
    }

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = synchronized(events) { events.filter { it.seq in fromSeq..toSeq } }

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> {
        val all = synchronized(events) { events.toList() }
        val start = all.indexOfLast { it.payload is Baseline }.coerceAtLeast(0)
        return all.drop(start)
    }

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = push

    override fun observeChanges(): Flow<Unit> = changes

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String {
        blobDir.mkdirs()
        val file = File(blobDir, name.substringAfterLast('/'))
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> {
        val last = events.lastOrNull()
        return listOf(
            ThreadSummary(
                threadId = ThreadId(DEBUG_THREAD_ID),
                title = "debug",
                createdAt = 0L,
                updatedAt = last?.createdAt ?: 0L,
                preview = null,
                lastRunStatus = null,
            ),
        )
    }

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ) {
        changes.tryEmit(Unit)
    }

    override suspend fun modelProfileOf(threadId: ThreadId): String? = null

    override suspend fun setModelProfile(
        threadId: ThreadId,
        profileId: String?,
    ) = Unit

    override suspend fun titleOf(threadId: ThreadId): String? = if (threadId.value == DEBUG_THREAD_ID) "debug" else null

    override suspend fun delete(threadId: ThreadId) {
        synchronized(events) { events.clear() }
        changes.tryEmit(Unit)
    }

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
}

/** 事件推送缓冲（调试页只消费少量事件）。 */
private const val PUSH_BUFFER: Int = 16
