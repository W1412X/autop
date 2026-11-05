/**
 * Run 启动前暂存的输入与触发上下文（进程内，docs/tech/07 §4.3）：
 * Run 启动即消费；进程被杀后未启动的 Run 会在恢复 WP 中按 `runs` 表重建输入。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.RunId
import com.example.autop.llm.Block
import com.example.autop.task.Task
import java.util.concurrent.ConcurrentHashMap

/** 一个待启动 Run 的暂存数据。 */
public data class PendingEntry(
    val input: List<Block>,
    val vars: Map<String, String>,
    val task: Task?,
)

/** 待启动 Run 暂存（入队写、执行读）。 */
public class PendingRuns {
    private val entries = ConcurrentHashMap<RunId, PendingEntry>()

    /** 登记。 */
    public fun put(
        runId: RunId,
        entry: PendingEntry,
    ) {
        entries[runId] = entry
    }

    /** 取出并移除（Run 启动时消费）。 */
    public fun take(runId: RunId): PendingEntry? = entries.remove(runId)

    /** 只移除（取消时清理）。 */
    public fun remove(runId: RunId) {
        entries.remove(runId)
    }

    /** 查询（调度门条件用）。 */
    public fun taskOf(runId: RunId): Task? = entries[runId]?.task
}
