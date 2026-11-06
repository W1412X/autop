package com.example.autop.flow.impl.run

import com.example.autop.core.common.Clock
import com.example.autop.flow.NodeStatus
import com.example.autop.flow.core.NodeTraceEnd
import com.example.autop.flow.core.NodeTraceStart
import com.example.autop.flow.core.RunCounters
import com.example.autop.flow.core.TracePort
import com.example.autop.flow.impl.db.FlowNodeRunEntity
import com.example.autop.flow.impl.store.RoomFlowRunStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一次运行的累计统计。 */
internal class RunTotals {
    val llmCalls = AtomicInteger()
    val llmTokens = AtomicLong()
    val apiCalls = AtomicInteger()
    val handoffs = AtomicInteger()
}

/** 执行记录端口：把节点开始/结束写入 `flow_node_runs`，统计在运行内累计。 */
internal class TracePortImpl(
    private val runId: String,
    private val store: RoomFlowRunStore,
    private val clock: Clock,
    private val maxRows: Int,
    val totals: RunTotals,
) : TracePort {
    private val seq = AtomicLong()
    private val started = ConcurrentHashMap<Long, Long>()

    override suspend fun nodeStarted(start: NodeTraceStart): Long {
        val now = clock.nowMillis()
        val rowId =
            store.nodeStarted(
                FlowNodeRunEntity(
                    runId = runId,
                    seq = seq.incrementAndGet(),
                    nodeId = start.nodeId,
                    nodeType = start.nodeType,
                    nodeName = start.nodeName,
                    path = start.path,
                    flowId = start.flowId,
                    status = RoomFlowRunStore.RUNNING_NODE,
                    errorCode = null,
                    message = null,
                    inputJson = null,
                    outputJson = null,
                    startedAt = now,
                    durationMs = 0,
                    attempts = 0,
                    llmTokens = 0,
                    attachmentsJson = "[]",
                ),
                maxRows,
            )
        if (rowId >= 0) started[rowId] = now
        return rowId
    }

    override suspend fun nodeFinished(
        handle: Long,
        end: NodeTraceEnd,
    ) {
        if (handle < 0) return
        val began = started.remove(handle) ?: clock.nowMillis()
        store.nodeFinished(
            rowId = handle,
            status = end.status,
            errorCode = end.errorCode,
            message = end.message,
            input = end.input,
            output = end.output,
            durationMs = clock.nowMillis() - began,
            attempts = end.attempts,
            llmTokens = end.llmTokens,
            attachments = end.attachments,
        )
    }

    override suspend fun log(
        level: String,
        message: String,
    ) {
        val rowId =
            store.nodeStarted(
                FlowNodeRunEntity(
                    runId = runId,
                    seq = seq.incrementAndGet(),
                    nodeId = "",
                    nodeType = LOG_TYPE,
                    nodeName = level,
                    path = "",
                    flowId = "",
                    status = RoomFlowRunStore.RUNNING_NODE,
                    errorCode = null,
                    message = message,
                    inputJson = null,
                    outputJson = null,
                    startedAt = clock.nowMillis(),
                    durationMs = 0,
                    attempts = 0,
                    llmTokens = 0,
                    attachmentsJson = "[]",
                ),
                maxRows,
            )
        if (rowId >= 0) store.nodeFinished(rowId, NodeStatus.OK, null, message, null, null, 0, 1, 0, emptyList())
    }

    override suspend fun counters(delta: RunCounters) {
        totals.llmCalls.addAndGet(delta.llmCalls)
        totals.llmTokens.addAndGet(delta.llmTokens)
        totals.apiCalls.addAndGet(delta.apiCalls)
        totals.handoffs.addAndGet(delta.handoffs)
    }

    private companion object {
        const val LOG_TYPE = "log"
    }
}
