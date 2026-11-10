/**
 * Run 启动前暂存的触发上下文（进程内，docs/tech/07 §4.3）：Run 启动即消费。
 * 只暂存 vars/task/flow；输入在入队时已写入事件日志（WP8.1）。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.RunId
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowRunOptions
import com.example.autop.task.Task
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** 流程运行的启动数据（独立运行或任务绑定的流程）。 */
public data class FlowLaunch(
    val definition: FlowDefinition,
    val params: JsonObject,
    val options: FlowRunOptions,
    val needsScreen: Boolean,
)

/** 一个待启动 Run 的暂存数据。 */
public data class PendingEntry(
    val vars: Map<String, String>,
    val task: Task?,
    /** 独立运行的流程；任务绑定的流程在执行时按 Task.flow 解析。 */
    val flow: FlowLaunch? = null,
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

    /** 查询（调度门条件用）：独立流程运行是否需要屏幕。 */
    public fun flowNeedsScreen(runId: RunId): Boolean? = entries[runId]?.flow?.needsScreen

    /** 查询（调度门条件用）。 */
    public fun taskOf(runId: RunId): Task? = entries[runId]?.task
}
