/**
 * agent 扩展点接口（docs/tech/04 §2.4）：工具结果后处理、历史渲染规则、压缩策略、
 * 用户交互与审计端口。
 */
package com.example.autop.agent

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.SystemPart
import com.example.autop.policy.PolicyDecision

/** 工具结果的后处理：可以在结果后追加注入消息（如首次进入某 App 时附 App 手册摘要）。 */
public interface ToolResultEnricher {
    public val id: String

    /** 返回的块作为 INJECTED user 消息，放在本批工具结果之后。 */
    public suspend fun enrich(
        toolId: String,
        output: ToolOutput,
        ctx: ToolContext,
    ): List<Block>
}

/** 历史渲染的一个中间项（docs/tech/04 §2.4）。 */
public data class RenderItem(
    val seq: Long,
    val msg: Msg,
    val observation: ObservationRef?,
    val toolId: String?,
    /** R1/B1：msg 是差量观测时的完整版本（来自 ToolResultEvent.fullBlocks）。 */
    val fullBlocks: List<Block>? = null,
)

/** 历史渲染规则：对“Baseline 之后的事件”转换得到的渲染项做变换。 */
public interface HistoryRenderRule {
    public val id: String

    public fun apply(
        items: List<RenderItem>,
        config: ConfigSnapshot,
    ): List<RenderItem>
}

/** 压缩请求：压缩策略需要的全部上下文。 */
public data class CompactionInput(
    val threadId: ThreadId,
    val system: List<SystemPart>,
    val live: List<RenderItem>,
    val userMessagesNewestFirst: List<Pair<Long, Msg>>,
    val plan: List<PlanItem>,
    val runVars: Map<String, String>,
    val lastObservation: RenderItem?,
    val config: ConfigSnapshot,
)

/** 压缩结果：新 Baseline 的消息与覆盖区间。 */
public data class CompactionResult(
    val baselineMessages: List<Msg>,
    val summaryText: String?,
    val coveredFromSeq: Long,
    val coveredToSeq: Long,
)

/** 压缩策略。 */
public interface Compactor {
    public val id: String

    public suspend fun compact(input: CompactionInput): Outcome<CompactionResult>
}

/** 用户交互端口：ask_user 与暂停后的继续。实现由 task-impl 提供。 */
public interface UserInteraction {
    public suspend fun ask(
        runId: RunId,
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String>
}

/** 审计端口：ToolRouter 对每次 WRITE_* 调用与策略决定调用一次。实现由 task-impl 提供。 */
public interface ToolAuditSink {
    public suspend fun record(
        runId: RunId,
        toolId: String,
        targetSummary: String?,
        decision: PolicyDecision,
        ok: Boolean?,
    )
}
