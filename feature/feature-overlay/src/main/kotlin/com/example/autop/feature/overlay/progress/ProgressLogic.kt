/**
 * 进度悬浮窗数据映射（docs/tech/08 §5.3，N182）：线程事件 → 消息行（模型见 `ProgressRow`）。
 * 独立于 Compose，供单测使用。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.runtime.Immutable
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.PauseReason
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.displayTextOf
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.example.autop.llm.Block
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/**
 * 线程事件 → 消息行（N182）：[PlanUpdated] 覆盖式（移除旧计划行再追加）；
 * 工具行取 `ToolResultEvent`（完成即定格成败），当前执行中的工具由窗口头部 currentStep 兜底；
 * Run 结束 → [ProgressRow.Finished] 元信息行附着末条助手文（N183）。
 *
 * N186 与对话页 `TimelineMerger` 逐项对齐（此前平行实现遗漏的修复回归）：
 * `INJECTED` 注入上下文不进消息流（不伪装用户气泡）、块按原序逐条出行、
 * 失败错误文本只取首个文本块、[live] 流式增量同款“持久化到达即作废”合并。
 */
public fun progressRowsOf(
    events: List<ThreadEvent>,
    live: List<AgentEvent> = emptyList(),
): List<ProgressRow> {
    val rows = mutableListOf<ProgressRow>()
    var startedAt: Long? = null
    var outputTokens = 0
    var finished: ThreadEvent? = null
    events.forEach { event ->
        when (val payload = event.payload) {
            is AssistantMessage -> outputTokens += payload.usage?.outputTokens ?: 0
            is RunStarted -> startedAt = event.createdAt
            is RunFinished -> finished = event
            else -> Unit
        }
        appendPayload(rows, event)
    }
    live.forEach { appendLive(rows, it) }
    // 结束元信息在 live 流式行之后处理（同对话页 appendResult 顺序，N186）。
    appendFinished(rows, finished, startedAt, outputTokens)
    return rows
}

/** 单个持久化事件 → 消息行（同对话页 appendPayload 的显示映射，N186）。 */
private fun appendPayload(
    rows: MutableList<ProgressRow>,
    event: ThreadEvent,
) {
    when (val payload = event.payload) {
        // INJECTED 为系统注入上下文（电量等），只进模型不进消息流（N174 对话页同款口径）。
        is UserMessage ->
            if (payload.kind != UserMessageKind.INJECTED) {
                rows +=
                    ProgressRow.User(
                        text = payload.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text },
                        isSteer = payload.kind == UserMessageKind.STEER,
                    )
            }
        is AssistantMessage -> appendAssistant(rows, payload)
        is ToolResultEvent ->
            rows +=
                ProgressRow.Tool(
                    toolId = payload.toolId,
                    ok = payload.ok,
                    durationMs = payload.durationMs,
                    errorText =
                        if (payload.ok) {
                            null
                        } else {
                            // 错误文本只取首个文本块（同对话页 resultText 口径），无则回退 errorCode。
                            payload.blocks
                                .filterIsInstance<Block.Text>()
                                .firstOrNull()
                                ?.text
                                ?.takeIf { it.isNotBlank() } ?: payload.errorCode
                        },
                )
        is PlanUpdated -> {
            rows.removeAll { it is ProgressRow.Plan }
            rows += ProgressRow.Plan(payload.items, payload.explanation)
        }
        else -> Unit
    }
}

/** 助手消息块按原序逐条出行（同对话页 appendMessage）：思考与文本交错时不再重排/合并。 */
private fun appendAssistant(
    rows: MutableList<ProgressRow>,
    payload: AssistantMessage,
) {
    payload.blocks.forEach { block ->
        when (block) {
            is Block.Thinking -> rows += ProgressRow.Thinking(block.text)
            // 回退协议/工具标记文本不冒充正文（displayTextOf 过滤）。
            is Block.Text ->
                displayTextOf(block.text).takeIf { it.isNotBlank() }?.let { shown ->
                    rows += ProgressRow.Assistant(shown)
                }
            // 工具调用行取 ToolResultEvent；Image/ToolResult 不进消息行（同对话页）。
            else -> Unit
        }
    }
}

/** 实时增量 → 追加或合并到流式行（同对话页 appendLive；工具行取 ToolResultEvent，不出行）。 */
private fun appendLive(
    rows: MutableList<ProgressRow>,
    event: AgentEvent,
) {
    when (event) {
        is AgentEvent.TextDelta -> appendStream(rows, event.text, thinking = false)
        is AgentEvent.ThinkingDelta -> appendStream(rows, event.text, thinking = true)
        else -> Unit
    }
}

/** 流式文本合并：追加到同类型流式行，否则新建（同对话页 appendStream）。 */
private fun appendStream(
    rows: MutableList<ProgressRow>,
    text: String,
    thinking: Boolean,
) {
    val last = rows.lastOrNull()
    if (thinking && last is ProgressRow.Thinking && last.streaming) {
        rows[rows.lastIndex] = last.copy(text = last.text + text)
    } else if (!thinking && last is ProgressRow.Assistant && last.streaming) {
        rows[rows.lastIndex] = last.copy(text = last.text + text)
    } else {
        rows +=
            if (thinking) {
                ProgressRow.Thinking(text, streaming = true)
            } else {
                ProgressRow.Assistant(text, streaming = true)
            }
    }
}

/**
 * Run 结束 → [ProgressRow.Finished]（summary 与末条助手文重复时丢弃，同对话页）；
 * 末条为助手文时附着其末尾，否则独立成行（N183）。
 */
private fun appendFinished(
    rows: MutableList<ProgressRow>,
    event: ThreadEvent?,
    startedAt: Long?,
    outputTokens: Int,
) {
    val payload = event?.payload as? RunFinished ?: return
    val trimmed = payload.summary?.trim()
    val duplicated = rows.any { (it as? ProgressRow.Assistant)?.text?.trim() == trimmed }
    val meta =
        ProgressRow.Finished(
            status = payload.status,
            summary = if (trimmed.isNullOrEmpty() || duplicated) null else payload.summary,
            stepCount = rows.count { it is ProgressRow.Tool },
            durationMs = event.createdAt - (startedAt ?: event.createdAt),
            outputTokens = outputTokens,
        )
    val lastIndex = rows.lastIndex
    val lastText = rows.getOrNull(lastIndex) as? ProgressRow.Assistant
    if (lastText != null) {
        rows[lastIndex] = lastText.copy(meta = meta)
    } else {
        rows += meta
    }
}

/** RunOutcome → 结束状态徽标文案（strings `progress_outcome_*`）。 */
public fun outcomeTextOf(outcome: RunOutcome): Int =
    when (outcome) {
        RunOutcome.SUCCEEDED -> R.string.progress_outcome_succeeded
        RunOutcome.FAILED -> R.string.progress_outcome_failed
        RunOutcome.CANCELLED -> R.string.progress_outcome_cancelled
        RunOutcome.BUDGET_EXHAUSTED -> R.string.progress_outcome_budget
        RunOutcome.INTERRUPTED -> R.string.progress_outcome_interrupted
    }

/**
 * 进度窗头部状态规格（N185）：状态色条/氛围色语义、状态徽标、提示行与运行控制可见性。
 * 运行中看 Run 状态；结束后回退最近结束元信息（色条与徽标变结果语义，控制行收起）。
 */
@Immutable
public data class ProgressHeaderSpec(
    /** 色条/氛围色/徽标的语义色键。 */
    val tone: StatusTone,
    /** 状态徽标文案（strings）。 */
    val chipText: Int,
    /** 提示行文案（strings；null = 显示当前步骤 currentStep）。 */
    val hint: Int?,
    /** 运行控制（暂停/停止/补充指令）是否可用（结束后收起）。 */
    val runControls: Boolean,
)

/** 头部状态推导（docs/tech/08 §5.3，N185，纯映射供单测）。 */
public fun progressHeaderSpec(
    active: ActiveRunInfo?,
    finished: ProgressRow.Finished?,
): ProgressHeaderSpec =
    when {
        active != null -> {
            val blocked = active.run.pauseReason == PauseReason.POLICY_BLOCKED
            val waiting = active.run.waitingQuestion != null
            ProgressHeaderSpec(
                tone =
                    when {
                        blocked -> StatusTone.BLOCKED
                        waiting -> StatusTone.WAITING
                        else -> statusToneOf(active.run.status)
                    },
                chipText = if (blocked) R.string.panel_status_blocked else statusTextOf(active.run.status),
                hint =
                    when {
                        waiting -> R.string.progress_hint_waiting
                        blocked -> R.string.progress_hint_blocked
                        active.run.status == RunStatus.PAUSED -> R.string.progress_hint_paused
                        else -> null
                    },
                runControls = true,
            )
        }
        finished != null ->
            ProgressHeaderSpec(
                tone = outcomeToneOf(finished.status),
                chipText = outcomeTextOf(finished.status),
                hint = null,
                runControls = false,
            )
        else ->
            ProgressHeaderSpec(
                tone = StatusTone.NEUTRAL,
                chipText = R.string.panel_status_queued,
                hint = null,
                runControls = false,
            )
    }

/** RunStatus → 状态徽标语义（自运行面板迁入，N182；N185 迁至数据映射层）。 */
internal fun statusToneOf(status: RunStatus?): StatusTone =
    when (status) {
        null, RunStatus.QUEUED, RunStatus.WAITING_SCREEN -> StatusTone.NEUTRAL
        RunStatus.WAITING_USER -> StatusTone.WAITING
        RunStatus.RUNNING -> StatusTone.RUNNING
        RunStatus.PAUSED -> StatusTone.PAUSED
        RunStatus.SUCCEEDED -> StatusTone.SUCCEEDED
        RunStatus.FAILED, RunStatus.BUDGET_EXHAUSTED -> StatusTone.BLOCKED
        RunStatus.CANCELLED, RunStatus.EXPIRED, RunStatus.INTERRUPTED -> StatusTone.NEUTRAL
    }

/** RunStatus → 状态徽标文案（strings 资源，自运行面板迁入，N182；N185 迁至数据映射层）。 */
internal fun statusTextOf(status: RunStatus?): Int =
    when (status) {
        null, RunStatus.QUEUED, RunStatus.WAITING_SCREEN -> R.string.panel_status_queued
        RunStatus.WAITING_USER -> R.string.panel_status_waiting
        RunStatus.RUNNING -> R.string.panel_status_running
        RunStatus.PAUSED -> R.string.panel_status_paused
        RunStatus.SUCCEEDED -> R.string.panel_status_succeeded
        RunStatus.FAILED -> R.string.panel_status_failed
        RunStatus.BUDGET_EXHAUSTED -> R.string.panel_status_budget
        RunStatus.CANCELLED, RunStatus.EXPIRED, RunStatus.INTERRUPTED -> R.string.panel_status_stopped
    }
