/**
 * 运行队列（docs/tech/07 §2，差异 D3）：按 origin 权重 + Task.priority 降序、
 * 入队时间升序的稳定优先级队列。由 RunCoordinator 的单协程串行访问，无并发保护。
 */
package com.example.autop.task.core

import com.example.autop.core.common.RunId
import com.example.autop.task.RunOrigin

/** 运行优先级队列。 */
public class RunQueue(
    /** origin 权重表（`task.queue.originWeights`，缺省按 0）。 */
    private val originWeights: Map<String, Int>,
) {
    /** 队列项。 */
    public data class Entry(
        val runId: RunId,
        val origin: RunOrigin,
        /** Task.priority（对话为 0），越大越先。 */
        val taskPriority: Int = 0,
        /** 入队时刻；同分时越早越先。 */
        val enqueuedAt: Long,
    )

    private val entries = mutableListOf<Entry>()

    /** 当前队列长度。 */
    public val size: Int
        get() = entries.size

    /** 入队并保持排序稳定（同分按入队先后）。 */
    public fun add(entry: Entry) {
        entries += entry
        entries.sortWith(scoreComparator())
    }

    /** 队首元素。 */
    public fun peek(): Entry? = entries.firstOrNull()

    /** 移除与 [entry] 同 [Entry.runId] 的项。 */
    public fun remove(entry: Entry) {
        entries.removeAll { it.runId == entry.runId }
    }

    /** 当前排序快照。 */
    public fun snapshot(): List<Entry> = entries.toList()

    private fun scoreComparator(): Comparator<Entry> =
        compareByDescending<Entry> { scoreOf(it) }.thenBy { it.enqueuedAt }

    private fun scoreOf(entry: Entry): Int = (originWeights[entry.origin.name] ?: 0) + entry.taskPriority
}
