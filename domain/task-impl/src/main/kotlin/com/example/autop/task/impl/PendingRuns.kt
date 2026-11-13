/**
 * Run 启动前暂存的触发上下文（进程内，docs/tech/07 §4.3）：Run 启动即消费。
 * 只暂存 vars/task/flow；输入在入队时已写入事件日志（WP8.1）。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.RunId
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowRunOptions
import com.example.autop.task.Task
import com.example.autop.task.TaskExecutor
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

    /** 取消意图（句柄尚未登记的启动窗口）：Run 启动后消费补投取消。 */
    private val cancelled = ConcurrentHashMap.newKeySet<RunId>()

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

    /** 落取消意图（幂等）：句柄未登记时点下的取消，begin 后由执行侧补投。 */
    public fun markCancelled(runId: RunId) {
        cancelled += runId
    }

    /** 消费取消意图：命中返回 true（调用方须立刻补投取消）。 */
    public fun consumeCancelled(runId: RunId): Boolean = cancelled.remove(runId)

    /** 查询（调度门条件用）：独立流程运行是否需要屏幕。 */
    public fun flowNeedsScreen(runId: RunId): Boolean? = entries[runId]?.flow?.needsScreen

    /** 查询（调度门条件用）。 */
    public fun taskOf(runId: RunId): Task? = entries[runId]?.task

    /** 是否为流程运行：独立流程，或任务绑定了流程（流程功能关闭时据此拦截，ADR-0030）。 */
    public fun isFlowRun(runId: RunId): Boolean {
        val entry = entries[runId] ?: return false
        val bound = entry.task?.let { it.executor == TaskExecutor.FLOW && it.flow != null } == true
        return entry.flow != null || bound
    }
}
