/**
 * 带观察的 ThreadStore 包装（docs/tech/04 §2.6）：所有 append 都以
 * `AgentEvent.Persisted` 转发给 UI；工具经 ToolContext 写入的事件同样被观察到。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 观察包装：append 串行化并在提交后回调 [onAppended]。 */
@Suppress("TooManyFunctions") // 纯转发包装，方法数即 ThreadStore 接口操作集
internal class ObservedThreadStore(
    private val delegate: ThreadStore,
    private val onAppended: suspend (ThreadEvent) -> Unit,
) : ThreadStore {
    private val mutex = Mutex()

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId = delegate.createThread(title, parent)

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent {
        val event = mutex.withLock { delegate.append(threadId, runId, payload) }
        onAppended(event)
        return event
    }

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = delegate.events(threadId, fromSeq, toSeq)

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> =
        delegate.eventsSinceLatestBaseline(threadId)

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = delegate.observe(threadId)

    override fun observeFrom(
        threadId: ThreadId,
        afterSeq: Long,
    ): Flow<List<ThreadEvent>> = delegate.observeFrom(threadId, afterSeq)

    override fun observeChanges(): Flow<Unit> = delegate.observeChanges()

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = delegate.saveBlob(threadId, name, bytes)

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> = delegate.listThreads(offset, limit)

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ) = delegate.rename(threadId, title)

    override suspend fun modelProfileOf(threadId: ThreadId): String? = delegate.modelProfileOf(threadId)

    override suspend fun setModelProfile(
        threadId: ThreadId,
        profileId: String?,
    ) = delegate.setModelProfile(threadId, profileId)

    override suspend fun titleOf(threadId: ThreadId): String? = delegate.titleOf(threadId)

    override suspend fun delete(threadId: ThreadId) = delegate.delete(threadId)

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = delegate.forkablePoints(threadId)
}
