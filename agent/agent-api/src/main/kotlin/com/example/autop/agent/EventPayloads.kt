/**
 * 线程持久化事件载荷（docs/tech/04 §2.1，v1）。
 *
 * 演进：payload 只增不改；改结构时 `v` +1 并在 `EventUpcaster` 注册升级变换，
 * 读取时先按 `type` 与 `v` 升级再解码。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 事件载荷基类：[v] 为载荷结构版本。 */
@Serializable
public sealed interface EventPayload {
    public val v: Int
}

/** Baseline 的来源：压缩 / 分叉 / 中断恢复。 */
@Serializable
public enum class BaselineReason {
    COMPACTION,
    FORK,
    RESUME,
}

/** 用户消息的种类：直接输入 / 运行中补充 / 系统注入。 */
@Serializable
public enum class UserMessageKind {
    INPUT,
    STEER,
    INJECTED,
}

/** 暂停原因（docs/tech/04 §2.1）。 */
@Serializable
public enum class PauseReason {
    /** N186 起不再产生（触摸盾取代触摸暂停）；保留枚举值兼容历史 RunRecord。 */
    USER_TOUCH,
    POLICY_BLOCKED,
    ASK_USER,
    LEASE_LOST,
    MANUAL,
}

/** Run 结束状态（docs/tech/04 §2.1）。 */
@Serializable
public enum class RunOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,

    /** 熔断 Stop 收口（连续无变化/同错误重复）：与预算耗尽区分，避免误标“预算用尽”。 */
    BREAKER_STOPPED,
    INTERRUPTED,
}

/** 计划项状态。 */
@Serializable
public enum class PlanStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    DROPPED,
}

/** 计划项。 */
@Serializable
public data class PlanItem(
    val step: String,
    val status: PlanStatus,
)

/** 屏幕观测的摘要引用（渲染降级时代替全文）。 */
@Serializable
public data class ObservationRef(
    val fingerprint: String,
    val packageName: String?,
    val activity: String?,
    val summaryLine: String,
    val nodeCount: Int,
    val hasImage: Boolean,
    /** R1/B1：本次观测文本是否为差量（相对于本 Run 上一次观测）。 */
    val isDiff: Boolean = false,
)

/** 线程创建。 */
@Serializable
@SerialName("thread_created")
public data class ThreadCreated(
    override val v: Int = 1,
    val title: String? = null,
    val parentThreadId: ThreadId? = null,
    val parentSeq: Long? = null,
) : EventPayload

/** 压缩与 fork 都写 Baseline；HistoryRenderer 从最近一条 Baseline 开始渲染。 */
@Serializable
@SerialName("baseline")
public data class Baseline(
    override val v: Int = 1,
    val reason: BaselineReason,
    /** 直接作为 live 视图的开头（不含 system）。 */
    val messages: List<Msg>,
    val coveredFromSeq: Long? = null,
    val coveredToSeq: Long? = null,
    val summaryText: String? = null,
    /** R1/B3：基线时刻的运行笔记，压缩与 fork 时原样带入。 */
    val notes: List<String> = emptyList(),
) : EventPayload

/** Run 开始：配置差异、prompt 版本与模型快照，供复盘。 */
@Serializable
@SerialName("run_started")
public data class RunStarted(
    override val v: Int = 1,
    val runId: RunId,
    val configDiff: JsonObject,
    val promptVersions: Map<String, Int>,
    /** 其中被用户覆盖（PromptSource.USER）的模板 id，复盘时区分内置与自定义提示词。 */
    val userPromptIds: Set<String> = emptySet(),
    val modelProfileId: String,
    val toolIds: List<String>,
) : EventPayload

/** 用户消息（INPUT | STEER | INJECTED）。 */
@Serializable
@SerialName("user_message")
public data class UserMessage(
    override val v: Int = 1,
    val blocks: List<Block>,
    val kind: UserMessageKind,
) : EventPayload

/** 助手消息（含 usage 与停止原因）。 */
@Serializable
@SerialName("assistant_message")
public data class AssistantMessage(
    override val v: Int = 1,
    val blocks: List<Block>,
    val usage: Usage?,
    val stopReason: StopReason,
    val modelProfileId: String,
    /** R1/C1：模型生成或回放合成。 */
    val origin: MessageOrigin = MessageOrigin.MODEL,
) : EventPayload

/** 工具结果（全量内容，不截断）。 */
@Serializable
@SerialName("tool_result")
public data class ToolResultEvent(
    override val v: Int = 1,
    val callId: String,
    val toolId: String,
    val ok: Boolean,
    val blocks: List<Block>,
    /** 结构化结果，给 UI。 */
    val data: JsonElement? = null,
    val errorCode: String? = null,
    /** 该结果是否包含屏幕观测（用于渲染降级）。 */
    val observation: ObservationRef? = null,
    val durationMs: Long,
    /** R1/B1：blocks 是差量观测时，这里存同一时刻的完整观测。 */
    val fullBlocks: List<Block>? = null,
    /** R1/A6：可回放动作记录（docs/tech/04 §2.7），只在成功时填写。 */
    val replay: ReplayableAction? = null,
    /** 屏幕变化结论（agent-harness-overhaul D4）；无屏幕语义的工具为 null。 */
    val screenDelta: ScreenDelta? = null,
) : EventPayload

/** R1/B3：本 Thread 的运行笔记（note 工具写入）。 */
@Serializable
@SerialName("run_note_added")
public data class RunNoteAdded(
    override val v: Int = 1,
    val text: String,
) : EventPayload

/** 计划更新。 */
@Serializable
@SerialName("plan_updated")
public data class PlanUpdated(
    override val v: Int = 1,
    val items: List<PlanItem>,
    /** 本次更新的说明（update_plan 的 explanation 参数）。 */
    val explanation: String? = null,
) : EventPayload

/** Run 暂停。 */
@Serializable
@SerialName("run_paused")
public data class RunPaused(
    override val v: Int = 1,
    val reason: PauseReason,
    val detail: String? = null,
) : EventPayload

/** Run 恢复。 */
@Serializable
@SerialName("run_resumed")
public data class RunResumed(
    override val v: Int = 1,
    val userNote: String? = null,
) : EventPayload

/** Run 结束。 */
@Serializable
@SerialName("run_finished")
public data class RunFinished(
    override val v: Int = 1,
    val status: RunOutcome,
    val summary: String?,
    val outputs: JsonObject? = null,
    val errorCode: String? = null,
    /** 最终导致失败的节点（流程运行的失败交回，flows.md §14.1）。 */
    val failedNodeId: String? = null,
    val failedNodeName: String? = null,
) : EventPayload
