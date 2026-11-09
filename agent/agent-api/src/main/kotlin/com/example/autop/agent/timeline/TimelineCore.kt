/**
 * 时间线归并内核（docs/tech/08 §4.2/§7）：把持久化 [ThreadEvent] 与实时 [AgentEvent]
 * 增量归并为中立条目 [CoreEntry]。纯函数、全量重算（事件量可控，换取简单可靠）。
 *
 * 对话页 `TimelineMerger` 与悬浮层 `ProgressLogic` 共用本内核：归并口径（按轮窗口
 * 统计、结束元信息结算、callId 配对转态、流式合并）单点维护，UI 差异只在各自映射层。
 */
package com.example.autop.agent.timeline

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.PauseReason
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.displayTextOf
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 步骤（工具调用）状态。 */
public enum class StepState {
    /** 调用中（发出即显示、结果未回）。 */
    RUNNING,

    /** 成功。 */
    DONE,

    /** 失败。 */
    FAILED,
}

/** 状态横幅种类（等待回答由提问卡承载，docs/tech/08 §4.2）。 */
public enum class BannerKind {
    /** 已暂停（含手动 / 触摸 / 租约丢失）。 */
    PAUSED,

    /** 被策略拦截。 */
    BLOCKED,

    /** 已中断（进程被杀等，恢复后提示）。 */
    INTERRUPTED,
}

/**
 * 步骤结果里的快捷跳转入口（工具结果 `data.links`）：由 app 按 [kind] 映射到任务/流程路由。
 *
 * [kind] 取值：task_detail / task_edit / task_ai / flow_detail / flow_edit / flow_ai。
 */
public data class StepLink(
    val kind: String,
    /** 展示文案（缺省按 kind 给默认标签）。 */
    val label: String? = null,
    val taskId: String? = null,
    val flowId: String? = null,
    val draftId: String? = null,
) {
    public companion object {
        public const val TASK_DETAIL: String = "task_detail"
        public const val TASK_EDIT: String = "task_edit"
        public const val TASK_AI: String = "task_ai"
        public const val FLOW_DETAIL: String = "flow_detail"
        public const val FLOW_EDIT: String = "flow_edit"
        public const val FLOW_AI: String = "flow_ai"
    }
}

/** 中立时间线条目（归并内核输出；UI 模块各自映射到自家模型）。 */
public sealed interface CoreRow {
    /** 用户消息（[isSteer] 为运行中补充指令；[images] 为随消息发送的图片 fileRef）。 */
    public data class User(
        val text: String,
        val isSteer: Boolean,
        val images: List<String> = emptyList(),
    ) : CoreRow

    /**
     * 助手文本（[streaming] 为流式未落盘增量，末尾显示光标）。
     * [meta] 为本轮 Run 结束的元信息行，附着在回复末尾（N183）。
     */
    public data class Assistant(
        val text: String,
        val streaming: Boolean = false,
        val meta: RunMeta? = null,
    ) : CoreRow

    /** 思考块（[streaming] 流式时实时展开，落定后折叠）。 */
    public data class Thinking(
        val text: String,
        val streaming: Boolean = false,
    ) : CoreRow

    /** 工具调用步骤行（发出即出行 [StepState.RUNNING]，结果到达转 DONE/FAILED）。 */
    public data class Step(
        val callId: String,
        val toolId: String,
        val argsJson: String,
        val state: StepState,
        val durationMs: Long?,
        val resultText: String?,
        val observationLine: String?,
        val imageRef: String?,
        /** 流式进度文本（ToolProgress）。 */
        val progressText: String?,
        /** 失败错误码（结果无文本时的错误回退展示）。 */
        val errorCode: String? = null,
        /** 结果上的快捷跳转入口（任务/流程工具）。 */
        val links: List<StepLink> = emptyList(),
    ) : CoreRow

    /** 计划卡（PlanUpdated 覆盖式更新）。 */
    public data class Plan(
        val items: List<PlanItem>,
        /** 本次更新的说明（update_plan 的 explanation）。 */
        val explanation: String? = null,
    ) : CoreRow

    /** Run 结束元信息（任务状态 + 步数/用时/Token 统计；附着回复末尾或独立成行）。 */
    public data class RunMeta(
        val outcome: RunOutcome,
        val summary: String?,
        val stepCount: Int,
        val durationMs: Long,
        val outputTokens: Int,
    ) : CoreRow

    /** 暂停 / 拦截 / 中断状态横幅。 */
    public data class Banner(
        val kind: BannerKind,
        val detail: String?,
    ) : CoreRow

    /** 分叉来源标记（仅分叉线程的 ThreadCreated 出行）。 */
    public data class ForkMarker(
        val parentThreadId: String,
        val parentSeq: Long,
    ) : CoreRow
}

/**
 * 归并结果条目。
 *
 * @param row 条目内容。
 * @param seq 源事件 seq（流式未落盘条目为 null）。
 * @param forkable 该处是否为可分叉点（docs/tech/04 §3.11：无未配对 ToolCall 的
 * ASSISTANT / TOOL 批次结束处）。
 */
public data class CoreEntry(
    val row: CoreRow,
    val seq: Long?,
    val forkable: Boolean,
)

/**
 * 归并时间线。
 *
 * @param history 已持久化事件（按 seq 升序）。
 * @param live 实时增量（[AgentEvent.Persisted] 忽略；文本增量按"持久化到达即作废"由
 * 调用方清缓存，避免与历史重复）。
 */
public fun mergeCore(
    history: List<ThreadEvent>,
    live: List<AgentEvent>,
): List<CoreEntry> {
    val entries = mutableListOf<CoreEntry>()
    var unpaired = 0
    // 按轮窗口：RunStarted 重置，步数/用时/Token 只统计本轮，结束元信息不串轮。
    var windowStart: Long? = null
    var windowTokens = 0
    var windowStartIndex = 0
    history.forEach { event ->
        when (val payload = event.payload) {
            is RunStarted -> {
                windowStart = event.createdAt
                windowTokens = 0
                windowStartIndex = entries.size
            }
            is AssistantMessage -> windowTokens += payload.usage?.outputTokens ?: 0
            is RunFinished -> appendResult(entries, event, windowStart, windowTokens, windowStartIndex)
            else -> Unit
        }
        unpaired = appendPayload(entries, event, unpaired)
    }
    live.forEach { appendLive(entries, it) }
    return entries
}

/** 单个持久化事件 → 条目；返回更新后的未配对 ToolCall 数。 */
private fun appendPayload(
    entries: MutableList<CoreEntry>,
    event: ThreadEvent,
    unpaired: Int,
): Int =
    when (val payload = event.payload) {
        is ThreadCreated -> {
            appendForkMarker(entries, event, payload)
            unpaired
        }
        is UserMessage -> {
            appendUser(entries, event, payload)
            unpaired
        }
        is AssistantMessage -> appendMessage(entries, event, payload, unpaired)
        is ToolResultEvent -> applyToolResult(entries, event, payload, unpaired)
        is PlanUpdated -> {
            entries.removeAll { it.row is CoreRow.Plan }
            entries.add(CoreEntry(CoreRow.Plan(payload.items, payload.explanation), event.seq, forkable = false))
            unpaired
        }
        is RunPaused -> {
            bannerKindOf(payload.reason)?.let { kind ->
                entries.add(
                    CoreEntry(CoreRow.Banner(kind, payload.detail), event.seq, forkable = false),
                )
            }
            unpaired
        }
        is RunResumed -> {
            val index = entries.indexOfLast { isUnresolvedBanner(it.row) }
            if (index >= 0) {
                entries.removeAt(index)
            }
            unpaired
        }
        is RunStarted,
        is RunFinished,
        is Baseline,
        is RunNoteAdded,
        -> unpaired
    }

/** 分叉来源线程的 ThreadCreated → ForkMarker（仅分叉线程有标记）。 */
private fun appendForkMarker(
    entries: MutableList<CoreEntry>,
    event: ThreadEvent,
    payload: ThreadCreated,
) {
    val parent = payload.parentThreadId ?: return
    entries.add(
        CoreEntry(
            CoreRow.ForkMarker(parent.value, payload.parentSeq ?: 0),
            event.seq,
            forkable = false,
        ),
    )
}

/** 用户消息 → User（INJECTED 的注入上下文不进时间线）。 */
private fun appendUser(
    entries: MutableList<CoreEntry>,
    event: ThreadEvent,
    payload: UserMessage,
) {
    if (payload.kind == UserMessageKind.INJECTED) return
    val text = payload.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
    val images = payload.blocks.filterIsInstance<Block.Image>().map { it.fileRef }
    val isSteer = payload.kind == UserMessageKind.STEER
    entries.add(
        CoreEntry(
            CoreRow.User(text, isSteer, images),
            event.seq,
            forkable = false,
        ),
    )
}

private fun appendMessage(
    entries: MutableList<CoreEntry>,
    event: ThreadEvent,
    payload: AssistantMessage,
    unpaired: Int,
): Int {
    var pairs = unpaired
    payload.blocks.forEach { block ->
        when (block) {
            is Block.Text ->
                // 回退协议/工具标记文本不冒充正文（displayTextOf 过滤，落盘原文不改）。
                displayTextOf(block.text).takeIf { it.isNotBlank() }?.let { shown ->
                    entries.add(
                        CoreEntry(CoreRow.Assistant(shown), event.seq, forkable = false),
                    )
                }
            is Block.Thinking ->
                entries.add(
                    CoreEntry(
                        CoreRow.Thinking(block.text),
                        event.seq,
                        forkable = false,
                    ),
                )
            is Block.ToolCall -> {
                entries.add(
                    CoreEntry(
                        CoreRow.Step(
                            callId = block.id,
                            toolId = block.name,
                            argsJson = block.argsJson,
                            state = StepState.RUNNING,
                            durationMs = null,
                            resultText = null,
                            observationLine = null,
                            imageRef = null,
                            progressText = null,
                        ),
                        event.seq,
                        forkable = false,
                    ),
                )
                pairs += 1
            }
            is Block.Image,
            is Block.ToolResult,
            -> Unit
        }
    }
    if (pairs == unpaired && entries.isNotEmpty()) {
        val last = entries.last()
        if (last.seq == event.seq) {
            entries[entries.lastIndex] = last.copy(forkable = true)
        }
    }
    return pairs
}

private fun applyToolResult(
    entries: MutableList<CoreEntry>,
    event: ThreadEvent,
    payload: ToolResultEvent,
    unpaired: Int,
): Int {
    val index = entries.indexOfLast { (it.row as? CoreRow.Step)?.callId == payload.callId }
    val previousArgs = (entries.getOrNull(index)?.row as? CoreRow.Step)?.argsJson ?: ""
    val row =
        CoreRow.Step(
            callId = payload.callId,
            toolId = payload.toolId,
            argsJson = previousArgs,
            state = if (payload.ok) StepState.DONE else StepState.FAILED,
            durationMs = payload.durationMs,
            resultText =
                payload.blocks
                    .filterIsInstance<Block.Text>()
                    .firstOrNull()
                    ?.text,
            observationLine = payload.observation?.summaryLine,
            imageRef =
                payload.blocks
                    .filterIsInstance<Block.Image>()
                    .firstOrNull()
                    ?.fileRef,
            progressText = null,
            errorCode = payload.errorCode,
            links = linksOf(payload.data),
        )
    val entry = CoreEntry(row, event.seq, forkable = unpaired <= 1)
    if (index >= 0) {
        entries[index] = entry
    } else {
        entries.add(entry)
    }
    return (unpaired - 1).coerceAtLeast(0)
}

/**
 * Run 结束 → [CoreRow.RunMeta]（本轮窗口统计：步数/用时/Token 只算本轮，附着在
 * **本轮**末条上——本轮无可见产出时独立成行，不覆盖上一轮消息上的元信息）。
 */
private fun appendResult(
    entries: MutableList<CoreEntry>,
    finished: ThreadEvent,
    startedAt: Long?,
    outputTokens: Int,
    windowStartIndex: Int,
) {
    val payload = finished.payload as? RunFinished ?: return
    val window = entries.subList(windowStartIndex, entries.size)
    val trimmed = payload.summary?.trim()
    val duplicated =
        window.any { entry ->
            (entry.row as? CoreRow.Assistant)?.text?.trim() == trimmed
        }
    val meta =
        CoreRow.RunMeta(
            outcome = payload.status,
            summary = if (trimmed.isNullOrEmpty() || duplicated) null else payload.summary,
            stepCount = window.count { it.row is CoreRow.Step },
            durationMs = finished.createdAt - (startedAt ?: finished.createdAt),
            outputTokens = outputTokens,
        )
    val last = window.lastOrNull()
    val lastText = last?.row as? CoreRow.Assistant
    if (last != null && lastText != null) {
        entries[entries.lastIndex] = last.copy(row = lastText.copy(meta = meta))
    } else {
        entries.add(CoreEntry(meta, finished.seq, forkable = false))
    }
}

/** 实时增量 → 追加或合并到流式条目。 */
private fun appendLive(
    entries: MutableList<CoreEntry>,
    event: AgentEvent,
) {
    when (event) {
        is AgentEvent.TextDelta -> appendStream(entries, event.text, thinking = false)
        is AgentEvent.ThinkingDelta -> appendStream(entries, event.text, thinking = true)
        is AgentEvent.ToolCallStreaming -> appendLiveStep(entries, event)
        is AgentEvent.ToolProgress -> applyToolProgress(entries, event)
        is AgentEvent.StateChanged,
        is AgentEvent.Persisted,
        -> Unit
    }
}

/**
 * 调用发出即出行（N192）：携带真实 callId 时按 id 就地配对——已有同 id 行则不重复出行，
 * 结果到达时 [applyToolResult] 按 callId 把它转成 DONE/FAILED（状态机闭环）。
 */
private fun appendLiveStep(
    entries: MutableList<CoreEntry>,
    event: AgentEvent.ToolCallStreaming,
) {
    val callId = event.callId.ifEmpty { "live-${event.index}" }
    val exists = entries.any { (it.row as? CoreRow.Step)?.callId == callId }
    if (exists) return
    entries.add(
        CoreEntry(
            CoreRow.Step(
                callId = callId,
                toolId = event.name,
                argsJson = "",
                state = StepState.RUNNING,
                durationMs = null,
                resultText = null,
                observationLine = null,
                imageRef = null,
                progressText = null,
            ),
            seq = null,
            forkable = false,
        ),
    )
}

/** 流式文本合并：追加到同类型流式条目，否则新建（docs/tech/08 §7 测试项）。 */
private fun appendStream(
    entries: MutableList<CoreEntry>,
    text: String,
    thinking: Boolean,
) {
    val last = entries.lastOrNull()?.takeIf { it.seq == null }
    val row = last?.row
    if (thinking && row is CoreRow.Thinking) {
        entries[entries.lastIndex] = last.copy(row = row.copy(text = row.text + text))
    } else if (!thinking && row is CoreRow.Assistant && row.streaming) {
        entries[entries.lastIndex] = last.copy(row = row.copy(text = row.text + text))
    } else {
        val new =
            if (thinking) {
                CoreRow.Thinking(text, streaming = true)
            } else {
                CoreRow.Assistant(text, streaming = true)
            }
        entries.add(CoreEntry(new, seq = null, forkable = false))
    }
}

private fun applyToolProgress(
    entries: MutableList<CoreEntry>,
    event: AgentEvent.ToolProgress,
) {
    val exact = entries.indexOfLast { (it.row as? CoreRow.Step)?.callId == event.callId }
    val index =
        if (exact >= 0) {
            exact
        } else {
            entries.indexOfLast { (it.row as? CoreRow.Step)?.state == StepState.RUNNING }
        }
    if (index < 0) return
    val step = entries[index].row as? CoreRow.Step ?: return
    entries[index] = entries[index].copy(row = step.copy(progressText = event.text))
}

/** 暂停原因 → 横幅种类（ASK_USER 由提问卡承载，返回 null）。 */
private fun bannerKindOf(reason: PauseReason): BannerKind? =
    when (reason) {
        PauseReason.POLICY_BLOCKED -> BannerKind.BLOCKED
        PauseReason.USER_TOUCH,
        PauseReason.LEASE_LOST,
        PauseReason.MANUAL,
        -> BannerKind.PAUSED
        PauseReason.ASK_USER -> null
    }

/** 未决横幅（RunResumed 到达时移除；中断横幅保留作历史提示）。 */
private fun isUnresolvedBanner(row: CoreRow): Boolean {
    val banner = row as? CoreRow.Banner ?: return false
    return banner.kind == BannerKind.PAUSED || banner.kind == BannerKind.BLOCKED
}

/** 工具结果 `data.links` → 快捷入口（宽松解析：结构不对的条目跳过，不影响时间线）。 */
private fun linksOf(data: JsonElement?): List<StepLink> {
    val array = (data as? JsonObject)?.get("links") as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonObject)?.toStepLink() }
        .distinctBy { listOf(it.kind, it.taskId, it.flowId, it.draftId) }
        .take(MAX_LINK_COUNT)
}

private fun JsonObject.toStepLink(): StepLink? {
    val kind = text("kind") ?: return null
    return StepLink(
        kind = kind,
        label = text("label"),
        taskId = text("taskId"),
        flowId = text("flowId"),
        draftId = text("draftId"),
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val MAX_LINK_COUNT: Int = 4
