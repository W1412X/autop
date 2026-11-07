/**
 * `flows.db` 实体（docs/design/flows.md §10）：流程文档整体以 JSON 存储；
 * 运行记录、节点明细、统计、状态、接口登记各一张表。
 */
package com.example.autop.flow.impl.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 流程库。 */
@Entity(tableName = "flows", indices = [Index("name")])
internal data class FlowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val json: String,
    val nodeCount: Int,
    val needsScreen: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** AI 创作的草稿（支持中途退出后继续）。 */
@Entity(tableName = "flow_drafts")
internal data class FlowDraftEntity(
    @PrimaryKey val draftId: String,
    val flowId: String?,
    val conversationJson: String,
    val draftJson: String?,
    /** 修订来由（update_flow / patch_flow_node 的 reason），确认生效时写进版本记录。 */
    val reason: String? = null,
    val updatedAt: Long,
)

/** 一次流程运行。 */
@Entity(tableName = "flow_runs", indices = [Index("flowId"), Index("startedAt"), Index("taskId")])
internal data class FlowRunEntity(
    @PrimaryKey val runId: String,
    val flowId: String,
    val flowName: String,
    val taskId: String?,
    val origin: String,
    val paramsJson: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long?,
    val errorCode: String?,
    val summary: String?,
    val llmCalls: Int,
    val llmTokens: Long,
    val apiCalls: Int,
    val handoffs: Int,
    val outputsJson: String?,
    val snapshotJson: String?,
)

/** 节点执行明细。 */
@Entity(tableName = "flow_node_runs", indices = [Index("runId"), Index("flowId", "nodeId")])
internal data class FlowNodeRunEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val runId: String,
    val seq: Long,
    val nodeId: String,
    val nodeType: String,
    val nodeName: String?,
    val path: String,
    val flowId: String,
    /** 节点运行中为 `running`；结束后为 [com.example.autop.flow.NodeStatus] 的 SerialName。 */
    val status: String,
    val errorCode: String?,
    val message: String?,
    val inputJson: String?,
    val outputJson: String?,
    val startedAt: Long,
    val durationMs: Long,
    val attempts: Int,
    val llmTokens: Long,
    val attachmentsJson: String,
)

/** 流程累计统计。 */
@Entity(tableName = "flow_stats")
internal data class FlowStatsEntity(
    @PrimaryKey val flowId: String,
    val totalRuns: Long,
    val succeeded: Long,
    val failed: Long,
    val cancelled: Long,
    val totalDurationMs: Long,
    val maxDurationMs: Long,
    val llmTokens: Long,
    val llmCalls: Long,
    val handoffs: Long,
)

/** 节点累计统计。 */
@Entity(tableName = "flow_node_stats", primaryKeys = ["flowId", "nodeId"])
internal data class FlowNodeStatsEntity(
    val flowId: String,
    val nodeId: String,
    val executions: Long,
    val failures: Long,
    val totalDurationMs: Long,
)

/** 跨运行状态。 */
@Entity(tableName = "flow_state", primaryKeys = ["scope", "stateKey"])
internal data class FlowStateEntity(
    val scope: String,
    val stateKey: String,
    val valueJson: String,
    val expiresAt: Long?,
    val updatedAt: Long,
)

/** 已登记的接口。 */
@Entity(tableName = "api_endpoints")
internal data class ApiEndpointEntity(
    @PrimaryKey val name: String,
    val baseUrl: String,
    val headersJson: String,
    val secretRef: String?,
    val secretHeader: String,
    val secretPrefix: String,
    val enabled: Boolean,
)

/** 流程的历史版本：覆盖保存前的定义（flows-recording-recovery.md A4）。 */
@Entity(tableName = "flow_versions", primaryKeys = ["flowId", "version"])
internal data class FlowVersionEntity(
    val flowId: String,
    val version: Int,
    val definitionJson: String,
    val createdAt: Long,
    val reason: String?,
)
