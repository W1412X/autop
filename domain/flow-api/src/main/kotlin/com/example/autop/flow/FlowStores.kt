/**
 * 流程存储端口（docs/design/flows.md §10、§11）。实现位于 flow-impl（flows.db）。
 */
package com.example.autop.flow

import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 流程库中的一条流程。 */
public data class StoredFlow(
    val id: FlowId,
    val definition: FlowDefinition,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 流程列表项（含统计摘要）。 */
public data class FlowListItem(
    val id: FlowId,
    val name: String,
    val description: String,
    val nodeCount: Int,
    val needsScreen: Boolean,
    val totalRuns: Long,
    val succeededRuns: Long,
    val lastRunAt: Long?,
    val lastRunStatus: String?,
    val updatedAt: Long,
)

/** 流程库。 */
public interface FlowStore {
    public fun observeAll(): Flow<List<FlowListItem>>

    public suspend fun get(id: FlowId): StoredFlow?

    /** 按名称或 ID 解析（名称不区分大小写）；重名返回第一个。 */
    public suspend fun resolve(nameOrId: String): StoredFlow?

    public suspend fun listAll(): List<StoredFlow>

    /** 保存（新建或覆盖）；调用方已完成校验。 */
    public suspend fun save(definition: FlowDefinition): Outcome<StoredFlow>

    public suspend fun delete(id: FlowId): Outcome<Unit>

    /** 导入时重名处理由调用方决定；此处只做 id 冲突检测。 */
    public suspend fun exists(id: FlowId): Boolean
}

/** 流程的一个历史版本。 */
public data class FlowVersion(
    val flowId: String,
    val version: Int,
    val definition: FlowDefinition,
    val createdAt: Long,
    val reason: String?,
)

/**
 * 流程版本留存（docs/design/flows-precondition-selfrepair.md S2）。
 *
 * 修订生效前把旧定义存进来，出问题可以回滚。恢复到某版本由调用方组合
 * `get(flowId, version)` + `FlowStore.save(definition)` 完成，恢复本身也会追加一个新版本。
 */
public interface FlowVersionStore {
    /** 保存一个历史版本；版本号在单事务内递增，避免并发撞号丢版本。 */
    public suspend fun save(
        flowId: String,
        definition: FlowDefinition,
        reason: String?,
    )

    /** 列出某流程的历史版本（新到旧）。 */
    public suspend fun list(flowId: String): List<FlowVersion>

    /** 取出某版本定义；不存在返回 null。 */
    public suspend fun get(
        flowId: String,
        version: Int,
    ): FlowDefinition?
}

/** 运行记录状态。 */
@Serializable
public enum class FlowRunStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("paused")
    PAUSED,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

/** 节点记录状态。 */
@Serializable
public enum class NodeStatus {
    @SerialName("ok")
    OK,

    @SerialName("failed")
    FAILED,

    @SerialName("skipped")
    SKIPPED,

    @SerialName("recovered")
    RECOVERED,
}

/** 一次流程运行摘要。 */
public data class FlowRunSummary(
    val runId: String,
    val flowId: String,
    val flowName: String,
    val taskId: String?,
    val origin: String,
    val params: JsonObject,
    val status: FlowRunStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long?,
    val errorCode: String?,
    val summary: String?,
    val llmCalls: Int,
    val llmTokens: Long,
    val apiCalls: Int,
    val handoffs: Int,
    val outputs: JsonObject?,
    /** 运行时的流程文档 JSON（事后查看当时结构）。 */
    val flowSnapshot: String?,
)

/** 一行节点执行记录。 */
public data class FlowNodeRecord(
    val rowId: Long,
    val runId: String,
    /** 嵌套深度（子流程、循环轮次通过 [path] 区分）。 */
    val seq: Long,
    val nodeId: String,
    val nodeType: String,
    val nodeName: String?,
    /** 形如 `n3[2]/n7`，循环轮次用 `[i]` 标出。 */
    val path: String,
    val flowId: String,
    val status: NodeStatus,
    val errorCode: String?,
    val message: String?,
    val input: JsonElement?,
    val output: JsonElement?,
    val startedAt: Long,
    val durationMs: Long,
    val attempts: Int,
    val llmTokens: Long,
    /** 截图等文件的相对路径。 */
    val attachments: List<String>,
)

/** 运行记录存储。 */
public interface FlowRunStore {
    public fun observeRuns(flowId: String?): Flow<List<FlowRunSummary>>

    public suspend fun getRun(runId: String): FlowRunSummary?

    /** 单次运行的摘要流（运行中状态与计数会更新）。 */
    public fun observeRun(runId: String): Flow<FlowRunSummary?>

    public suspend fun nodes(runId: String): List<FlowNodeRecord>

    public fun observeNodes(runId: String): Flow<List<FlowNodeRecord>>

    public suspend fun deleteRun(runId: String)

    public suspend fun deleteRunsOf(flowId: String)

    public suspend fun stats(flowId: String): FlowStats

    public suspend fun nodeStats(flowId: String): List<FlowNodeStats>
}

/** 流程累计统计。 */
public data class FlowStats(
    val totalRuns: Long,
    val succeeded: Long,
    val failed: Long,
    val cancelled: Long,
    val totalDurationMs: Long,
    val maxDurationMs: Long,
    val llmTokens: Long,
    val llmCalls: Long,
    val handoffs: Long,
) {
    public val successRate: Double
        get() = if (totalRuns == 0L) 0.0 else succeeded.toDouble() / totalRuns

    public val avgDurationMs: Long
        get() = if (totalRuns == 0L) 0 else totalDurationMs / totalRuns
}

/** 节点累计统计。 */
public data class FlowNodeStats(
    val nodeId: String,
    val executions: Long,
    val failures: Long,
    val totalDurationMs: Long,
) {
    public val failureRate: Double
        get() = if (executions == 0L) 0.0 else failures.toDouble() / executions
}

/** 跨运行状态（§10.1）。 */
public interface FlowStateStore {
    /** 读取；过期或不存在返回 null。 */
    public suspend fun get(
        scope: String,
        key: String,
    ): JsonElement?

    public suspend fun set(
        scope: String,
        key: String,
        value: JsonElement,
        ttlMs: Long?,
    )

    public suspend fun delete(
        scope: String,
        key: String,
    )

    public suspend fun list(scope: String): Map<String, JsonElement>

    public suspend fun clearScope(scope: String)

    public companion object {
        public fun taskScope(taskId: String): String = "task:$taskId"

        public fun flowScope(flowId: String): String = "flow:$flowId"
    }
}

/** 已登记的接口（§11）。 */
public data class ApiEndpoint(
    val name: String,
    val baseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    /** 密钥在 SecretStore 中的引用 id；null = 无密钥。 */
    val secretRef: String? = null,
    /** 密钥放入的请求头名，默认 Authorization。 */
    val secretHeader: String = "Authorization",
    /** 密钥值前缀，如 `Bearer `。 */
    val secretPrefix: String = "Bearer ",
    val enabled: Boolean = true,
)

/** 接口登记库。 */
public interface EndpointStore {
    public fun observeAll(): Flow<List<ApiEndpoint>>

    public suspend fun get(name: String): ApiEndpoint?

    /** 保存；[secret] 非 null 时写入 SecretStore 并更新引用。 */
    public suspend fun save(
        endpoint: ApiEndpoint,
        secret: String?,
    ): Outcome<ApiEndpoint>

    public suspend fun delete(name: String)
}
