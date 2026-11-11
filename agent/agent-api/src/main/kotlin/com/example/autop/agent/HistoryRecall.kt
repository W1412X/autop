/**
 * 历史检索扩展点（docs/tech/04 §2.5，差异 D4）：
 * `history_recall` 的 query 模式由“索引器 + 检索通道 + 融合”三部分组成，
 * 三者都是接口；新增检索方式 = 新增一个索引器和一个通道并在配置里启用。
 */
package com.example.autop.agent

import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block

/** 事件写入后被调用（异步、提交之后），为某个检索通道建立索引。 */
public interface ThreadEventIndexer {
    /** 稳定 id（`indexer.fts` | `indexer.vector`）。 */
    public val id: String

    public fun accepts(event: ThreadEvent): Boolean

    /** [text] 由 [RecallTextExtractor] 统一提取。 */
    public suspend fun index(
        event: ThreadEvent,
        text: String,
    )

    public suspend fun deleteThread(threadId: ThreadId)

    /** 删除这些事件（seq 全局唯一）的索引行；分支删除时用于清理不再被引用的事件。 */
    public suspend fun deleteEvents(seqs: List<Long>)

    /** 重建整个线程的索引（如 embedding 模型变化后）。 */
    public suspend fun rebuild(
        threadId: ThreadId,
        events: List<ThreadEvent>,
    )
}

/** 检索通道。 */
public interface ThreadRecallRetriever {
    /** 稳定 id（`recall.fts` | `recall.vector`）。 */
    public val id: String

    /** 如向量通道在 embedding 未配置时返回 false；不可用的通道跳过，不报错。 */
    public suspend fun isAvailable(): Boolean

    public suspend fun search(
        threadId: ThreadId,
        query: String,
        limit: Int,
    ): List<RecallCandidate>
}

/** 单个通道的检索候选。 */
public data class RecallCandidate(
    val seq: Long,
    val rank: Int,
    val snippet: String?,
)

/** 融合入口，history_recall 工具只依赖它。实现在 agent-runtime。 */
public interface ThreadRecall {
    public suspend fun search(
        threadId: ThreadId,
        query: String,
        k: Int,
    ): List<RecallHit>
}

/**
 * 对话索引维护端口（docs/tech/10 WP10"为历史对话重建向量索引"，08 §4.5
 * "重建对话索引"进度）：embedding 配置变化或用户手动触发时全量重建。
 */
public interface ThreadIndexMaintenance {
    /** 全量重建全部线程的检索索引；按线程报告 `done/total` 进度。 */
    public suspend fun rebuildAll(onProgress: (done: Int, total: Int) -> Unit)
}

/** 融合后的命中。 */
public data class RecallHit(
    val seq: Long,
    val type: String,
    val snippet: String,
    val score: Double,
    val channels: Set<String>,
)

/** 从事件中提取用于索引的文本（docs/tech/04 §2.5）。 */
public object RecallTextExtractor {
    /**
     * UserMessage（INPUT/STEER）、AssistantMessage 的 Text 块、
     * ToolResultEvent 首个 Text 块的前 [maxToolChars] 字；其他事件返回 null。
     */
    public fun extract(
        event: ThreadEvent,
        maxToolChars: Int,
    ): String? =
        when (val payload = event.payload) {
            is UserMessage ->
                if (payload.kind == UserMessageKind.INJECTED) {
                    null
                } else {
                    textOf(payload.blocks)
                }
            is AssistantMessage -> textOf(payload.blocks)
            is ToolResultEvent ->
                payload.blocks
                    .filterIsInstance<Block.Text>()
                    .firstOrNull()
                    ?.text
                    ?.take(maxToolChars)
            else -> null
        }

    private fun textOf(blocks: List<Block>): String? {
        val text =
            blocks
                .filterIsInstance<Block.Text>()
                .joinToString("\n") { it.text }
        return text.ifEmpty { null }
    }
}
