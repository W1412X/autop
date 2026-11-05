/**
 * 索引队列（docs/tech/04 §4/§2.5）：`ThreadStore.append` 提交后把事件交给本队列，
 * 单协程消费、有界队列，满时丢弃最旧并记 warn；索引失败只记日志，不影响主流程。
 */
package com.example.autop.agent.store.recall

import com.example.autop.agent.RecallTextExtractor
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadEventIndexer
import com.example.autop.core.common.Logger
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

/** 事件索引调度。 */
public class IndexQueue(
    private val indexers: Set<ThreadEventIndexer>,
    private val capacity: Int,
    private val config: ConfigProvider,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private data class Item(
        val event: ThreadEvent,
        val text: String,
    )

    private val channel = Channel<Item>(capacity = capacity)
    private val pending = AtomicLong(0)
    private var consumer: Job? = null

    /** 启动消费协程（幂等）。 */
    fun start() {
        if (consumer != null) return
        consumer =
            scope.launch {
                for (item in channel) {
                    indexSafely(item)
                    pending.decrementAndGet()
                }
            }
    }

    /** 提交事件：文本由 [RecallTextExtractor] 统一提取；满时丢弃最旧。 */
    fun submit(event: ThreadEvent) {
        val text = RecallTextExtractor.extract(event, config.current()[AgentConfig.STORE].ftsToolResultChars) ?: return
        start()
        pending.incrementAndGet()
        if (channel.trySend(Item(event, text)).isFailure) {
            channel.tryReceive().getOrNull()?.let {
                pending.decrementAndGet()
                logger.w(tag = "IndexQueue", event = "queue_full_dropped_oldest", "seq" to it.event.seq)
            }
            channel.trySend(Item(event, text))
        }
    }

    /** 等待已提交的事件全部索引完（测试与重建用）。 */
    suspend fun flush() {
        while (pending.get() > 0) {
            yield()
        }
    }

    /** 删除线程的全部索引。 */
    suspend fun deleteThread(threadId: ThreadId) {
        indexers.forEach { runSafely(it.id) { it.deleteThread(threadId) } }
    }

    /** 重建线程索引（如 embedding 模型变化后）。 */
    suspend fun rebuild(
        threadId: ThreadId,
        events: List<ThreadEvent>,
    ) {
        indexers.forEach { indexer ->
            runSafely(indexer.id) { indexer.rebuild(threadId, events) }
        }
    }

    private suspend fun indexSafely(item: Item) {
        for (indexer in indexers) {
            if (!indexer.accepts(item.event)) continue
            runSafely(indexer.id) { indexer.index(item.event, item.text) }
        }
    }

    // 索引器是插件面，任何异常都不能打断索引队列，故捕获 Throwable 并降级记日志
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runSafely(
        id: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            logger.w(tag = "IndexQueue", event = "indexer_failed", "indexer" to id, error = t)
        }
    }
}
