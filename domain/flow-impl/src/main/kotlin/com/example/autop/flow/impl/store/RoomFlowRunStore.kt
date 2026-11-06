@file:Suppress("LongParameterList") // 节点记录的字段集合

package com.example.autop.flow.impl.store

import androidx.room.withTransaction
import com.example.autop.core.common.AutopJson
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.flow.FlowNodeRecord
import com.example.autop.flow.FlowNodeStats
import com.example.autop.flow.FlowRunStatus
import com.example.autop.flow.FlowRunStore
import com.example.autop.flow.FlowRunSummary
import com.example.autop.flow.FlowStats
import com.example.autop.flow.NodeStatus
import com.example.autop.flow.impl.db.FlowNodeRunEntity
import com.example.autop.flow.impl.db.FlowNodeStatsEntity
import com.example.autop.flow.impl.db.FlowRunEntity
import com.example.autop.flow.impl.db.FlowStatsEntity
import com.example.autop.flow.impl.db.FlowsDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** 一次运行结束时写入的字段。 */
internal data class RunEnd(
    val status: FlowRunStatus,
    val endedAt: Long,
    val errorCode: String?,
    val summary: String?,
    val outputs: JsonObject?,
    val llmCalls: Int,
    val llmTokens: Long,
    val apiCalls: Int,
    val handoffs: Int,
)

/**
 * 执行记录与统计（`flow_runs` / `flow_node_runs` / `flow_stats` / `flow_node_stats`）。
 * 大文本（超过 `flow.trace.inlineMaxChars`）落文件，库里只留 `{"__file": 相对路径}`。
 */
internal class RoomFlowRunStore(
    private val db: FlowsDb,
    private val filesDir: File,
    private val configProvider: ConfigProvider,
) : FlowRunStore {
    private val json = AutopJson.default

    // ---- 写入 ---------------------------------------------------------------------------------

    suspend fun begin(entity: FlowRunEntity) = db.runDao().upsert(entity)

    suspend fun finish(
        runId: String,
        end: RunEnd,
    ) {
        db.withTransaction {
            val run = db.runDao().byId(runId) ?: return@withTransaction
            val duration = end.endedAt - run.startedAt
            db.runDao().upsert(
                run.copy(
                    status = statusName(end.status),
                    endedAt = end.endedAt,
                    durationMs = duration,
                    errorCode = end.errorCode,
                    summary = end.summary,
                    llmCalls = end.llmCalls,
                    llmTokens = end.llmTokens,
                    apiCalls = end.apiCalls,
                    handoffs = end.handoffs,
                    outputsJson = end.outputs?.toString(),
                ),
            )
            accumulate(run.flowId, end, duration)
        }
    }

    private suspend fun accumulate(
        flowId: String,
        end: RunEnd,
        duration: Long,
    ) {
        val s = db.statsDao().stats(flowId) ?: FlowStatsEntity(flowId, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        db.statsDao().upsertStats(
            s.copy(
                totalRuns = s.totalRuns + 1,
                succeeded = s.succeeded + if (end.status == FlowRunStatus.SUCCEEDED) 1 else 0,
                failed = s.failed + if (end.status == FlowRunStatus.FAILED) 1 else 0,
                cancelled = s.cancelled + if (end.status == FlowRunStatus.CANCELLED) 1 else 0,
                totalDurationMs = s.totalDurationMs + duration,
                maxDurationMs = maxOf(s.maxDurationMs, duration),
                llmTokens = s.llmTokens + end.llmTokens,
                llmCalls = s.llmCalls + end.llmCalls,
                handoffs = s.handoffs + end.handoffs,
            ),
        )
    }

    suspend fun setRunStatus(
        runId: String,
        status: FlowRunStatus,
    ) {
        val run = db.runDao().byId(runId) ?: return
        db.runDao().upsert(run.copy(status = statusName(status)))
    }

    suspend fun nodeStarted(
        entity: FlowNodeRunEntity,
        maxRows: Int,
    ): Long {
        if (maxRows > 0 && db.runDao().nodeCount(entity.runId) >= maxRows) return -1
        return db.runDao().insertNode(entity)
    }

    suspend fun nodeFinished(
        rowId: Long,
        status: NodeStatus,
        errorCode: String?,
        message: String?,
        input: JsonElement?,
        output: JsonElement?,
        durationMs: Long,
        attempts: Int,
        llmTokens: Long,
        attachments: List<String>,
    ) {
        val row = db.runDao().nodeById(rowId) ?: return
        db.runDao().updateNode(
            row.copy(
                status = nodeStatusName(status),
                errorCode = errorCode,
                message = message,
                inputJson = spill(row.runId, rowId, "in", input),
                outputJson = spill(row.runId, rowId, "out", output),
                durationMs = durationMs,
                attempts = attempts,
                llmTokens = llmTokens,
                attachmentsJson = json.encodeToString(ListSerializer(String.serializer()), attachments),
            ),
        )
        if (row.nodeId.isNotBlank()) bumpNodeStats(row.flowId, row.nodeId, status == NodeStatus.FAILED, durationMs)
    }

    private suspend fun bumpNodeStats(
        flowId: String,
        nodeId: String,
        failed: Boolean,
        duration: Long,
    ) {
        val s = db.statsDao().nodeStat(flowId, nodeId) ?: FlowNodeStatsEntity(flowId, nodeId, 0, 0, 0)
        db.statsDao().upsertNodeStats(
            s.copy(
                executions = s.executions + 1,
                failures = s.failures + if (failed) 1 else 0,
                totalDurationMs = s.totalDurationMs + duration,
            ),
        )
    }

    /** 启动时把上次未结束的运行标记为失败（进程被杀）。 */
    suspend fun markInterrupted(now: Long): Int {
        val list = db.runDao().unfinished()
        for (run in list) {
            db.runDao().upsert(
                run.copy(
                    status = statusName(FlowRunStatus.FAILED),
                    endedAt = now,
                    durationMs = now - run.startedAt,
                    errorCode = INTERRUPTED,
                ),
            )
        }
        return list.size
    }

    // ---- 读取 ---------------------------------------------------------------------------------

    suspend fun tokensSince(since: Long): Long = db.runDao().tokensSince(since)

    suspend fun lastRun(flowId: String): FlowRunSummary? = db.runDao().last(flowId)?.let(::toSummary)

    override fun observeRuns(flowId: String?): Flow<List<FlowRunSummary>> =
        db.runDao().observe(flowId).map { list -> list.map(::toSummary) }

    override fun observeRun(runId: String): Flow<FlowRunSummary?> =
        db.runDao().observeById(runId).map {
            it?.let(::toSummary)
        }

    override suspend fun getRun(runId: String): FlowRunSummary? = db.runDao().byId(runId)?.let(::toSummary)

    override suspend fun nodes(runId: String): List<FlowNodeRecord> = db.runDao().nodes(runId).map(::toRecord)

    override fun observeNodes(runId: String): Flow<List<FlowNodeRecord>> =
        db.runDao().observeNodes(runId).map { list -> list.map(::toRecord) }

    override suspend fun deleteRun(runId: String) {
        db.runDao().deleteNodes(runId)
        db.runDao().delete(runId)
        File(filesDir, "$BLOB_DIR/$runId").deleteRecursively()
    }

    override suspend fun deleteRunsOf(flowId: String) = deleteAllOf(flowId)

    suspend fun deleteAllOf(flowId: String) {
        db.runDao().deleteNodesByFlow(flowId)
        db.runDao().deleteByFlow(flowId)
        db.statsDao().deleteStats(flowId)
        db.statsDao().deleteNodeStats(flowId)
    }

    override suspend fun stats(flowId: String): FlowStats {
        val s = db.statsDao().stats(flowId)
        return FlowStats(
            s?.totalRuns ?: 0,
            s?.succeeded ?: 0,
            s?.failed ?: 0,
            s?.cancelled ?: 0,
            s?.totalDurationMs ?: 0,
            s?.maxDurationMs ?: 0,
            s?.llmTokens ?: 0,
            s?.llmCalls ?: 0,
            s?.handoffs ?: 0,
        )
    }

    override suspend fun nodeStats(flowId: String): List<FlowNodeStats> =
        db
            .statsDao()
            .nodeStats(flowId)
            .map { FlowNodeStats(it.nodeId, it.executions, it.failures, it.totalDurationMs) }
            .sortedByDescending { it.failureRate }

    // ---- 映射与大文本 -------------------------------------------------------------------------

    private fun toSummary(e: FlowRunEntity): FlowRunSummary =
        FlowRunSummary(
            runId = e.runId,
            flowId = e.flowId,
            flowName = e.flowName,
            taskId = e.taskId,
            origin = e.origin,
            params = json.parseToJsonElement(e.paramsJson) as? JsonObject ?: JsonObject(emptyMap()),
            status = statusOf(e.status),
            startedAt = e.startedAt,
            endedAt = e.endedAt,
            durationMs = e.durationMs,
            errorCode = e.errorCode,
            summary = e.summary,
            llmCalls = e.llmCalls,
            llmTokens = e.llmTokens,
            apiCalls = e.apiCalls,
            handoffs = e.handoffs,
            outputs = e.outputsJson?.let { json.parseToJsonElement(it) as? JsonObject },
            flowSnapshot = e.snapshotJson,
        )

    private fun toRecord(e: FlowNodeRunEntity): FlowNodeRecord =
        FlowNodeRecord(
            rowId = e.rowId,
            runId = e.runId,
            seq = e.seq,
            nodeId = e.nodeId,
            nodeType = e.nodeType,
            nodeName = e.nodeName,
            path = e.path,
            flowId = e.flowId,
            status = nodeStatusOf(e.status),
            errorCode = e.errorCode,
            message = e.message,
            input = unspill(e.inputJson),
            output = unspill(e.outputJson),
            startedAt = e.startedAt,
            durationMs = e.durationMs,
            attempts = e.attempts,
            llmTokens = e.llmTokens,
            attachments = json.decodeFromString(ListSerializer(String.serializer()), e.attachmentsJson),
        )

    private fun spill(
        runId: String,
        rowId: Long,
        kind: String,
        value: JsonElement?,
    ): String? {
        if (value == null || value is JsonNull) return null
        val text = value.toString()
        val limit = configProvider.current()[FlowConfig.TRACE].inlineMaxChars
        if (text.length <= limit) return text
        val rel = "$BLOB_DIR/$runId/$rowId-$kind.json"
        val file = File(filesDir, rel)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return JsonObject(mapOf(FILE_KEY to JsonPrimitive(rel))).toString()
    }

    private fun unspill(raw: String?): JsonElement? {
        if (raw == null) return null
        val element = json.parseToJsonElement(raw)
        val rel =
            ((element as? JsonObject)?.takeIf { it.size == 1 }?.get(FILE_KEY) as? JsonPrimitive)?.content
                ?: return element
        val file = File(filesDir, rel)
        return if (file.exists()) json.parseToJsonElement(file.readText()) else element
    }

    private fun statusName(s: FlowRunStatus): String = s.name.lowercase()

    private fun statusOf(raw: String): FlowRunStatus =
        FlowRunStatus.entries.firstOrNull { it.name.lowercase() == raw } ?: FlowRunStatus.FAILED

    private fun nodeStatusName(s: NodeStatus): String = s.name.lowercase()

    private fun nodeStatusOf(raw: String): NodeStatus =
        NodeStatus.entries.firstOrNull { it.name.lowercase() == raw } ?: NodeStatus.FAILED

    companion object {
        const val RUNNING_NODE: String = "running"
        const val INTERRUPTED: String = "flow.interrupted"
        private const val BLOB_DIR = "flows"
        private const val FILE_KEY = "__file"
    }
}
