/**
 * 检索索引维护（docs/tech/04 §2.5）：embedding 配置变化后由设置页调用，
 * 重建指定线程的 FTS/向量索引。
 */
package com.example.autop.agent.store

import com.example.autop.agent.ThreadIndexMaintenance
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.store.recall.IndexQueue
import com.example.autop.core.common.ThreadId

/** 重建 [threadId] 的检索索引（全量事件重放给各索引器）。 */
public suspend fun ThreadStore.rebuildIndexes(
    indexQueue: IndexQueue,
    threadId: ThreadId,
) {
    indexQueue.rebuild(threadId, events(threadId, 0, Long.MAX_VALUE).filter { it.threadId == threadId })
}

/**
 * [ThreadIndexMaintenance] 实现（docs/tech/10 WP10"为历史对话重建向量索引"）：
 * 分页拉全部线程逐个重建，按线程报告进度。
 */
public class ThreadIndexMaintenanceImpl(
    private val store: ThreadStore,
    private val indexQueue: IndexQueue,
) : ThreadIndexMaintenance {
    override suspend fun rebuildAll(onProgress: (done: Int, total: Int) -> Unit) {
        val threads = allThreads()
        onProgress(0, threads.size)
        threads.forEachIndexed { index, summary ->
            store.rebuildIndexes(indexQueue, summary.threadId)
            onProgress(index + 1, threads.size)
        }
    }

    private suspend fun allThreads(): List<ThreadSummary> {
        val result = mutableListOf<ThreadSummary>()
        while (true) {
            val page = store.listThreads(offset = result.size, limit = PAGE_SIZE)
            result += page
            if (page.size < PAGE_SIZE) return result
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
