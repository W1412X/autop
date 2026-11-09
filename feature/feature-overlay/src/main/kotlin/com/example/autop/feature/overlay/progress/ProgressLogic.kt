/**
 * 进度悬浮窗数据映射（docs/tech/08 §5.3，N182）：线程事件 → 消息行（模型见 `ProgressRow`）。
 * 公共归并内核 `mergeCore` 的悬浮层映射（与对话页 `TimelineMerger` 共用归并口径：
 * 按轮窗口统计、结束元信息结算、callId 配对转态、流式合并），此处只做 UI 模型转换。
 * 独立于 Compose，供单测使用。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.runtime.Immutable
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.timeline.CoreRow
import com.example.autop.agent.timeline.StepState
import com.example.autop.agent.timeline.mergeCore
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/**
 * 线程事件 → 消息行（N182）：[CoreRow.Plan] 覆盖式（内核保证移除旧计划行再追加）；
 * 工具行发出即出行（RUNNING，N192 对齐对话页），结果到达转 DONE/FAILED；
 * Run 结束 → [ProgressRow.Finished] 元信息行附着**本轮**末条助手文（N183）。
 */
public fun progressRowsOf(
    events: List<ThreadEvent>,
    live: List<AgentEvent> = emptyList(),
): List<ProgressRow> = mergeCore(events, live).mapNotNull { it.row.toProgressRow() }

/** 中立条目 → 消息行（对话页专属的横幅/分叉标记不出现在悬浮层消息流）。 */
private fun CoreRow.toProgressRow(): ProgressRow? =
    when (this) {
        is CoreRow.User -> ProgressRow.User(text, isSteer)
        is CoreRow.Assistant -> ProgressRow.Assistant(text, streaming, meta?.toFinished())
        is CoreRow.Thinking -> ProgressRow.Thinking(text, streaming)
        is CoreRow.Step -> toToolRow()
        is CoreRow.Plan -> ProgressRow.Plan(items, explanation)
        is CoreRow.RunMeta -> toFinished()
        is CoreRow.Banner,
        is CoreRow.ForkMarker,
        -> null
    }

private fun CoreRow.RunMeta.toFinished(): ProgressRow.Finished =
    ProgressRow.Finished(
        status = outcome,
        summary = summary,
        stepCount = stepCount,
        durationMs = durationMs,
        outputTokens = outputTokens,
    )

private fun CoreRow.Step.toToolRow(): ProgressRow.Tool =
    ProgressRow.Tool(
        toolId = toolId,
        state = state,
        durationMs = durationMs,
        // 错误文本只取首个文本块（同对话页 resultText 口径），无则回退 errorCode。
        errorText =
            if (state == StepState.FAILED) {
                resultText?.takeIf { it.isNotBlank() } ?: errorCode
            } else {
                null
            },
        progressText = progressText,
    )

/** RunOutcome → 结束状态徽标文案（strings `progress_outcome_*`）。 */
public fun outcomeTextOf(outcome: RunOutcome): Int =
    when (outcome) {
        RunOutcome.SUCCEEDED -> R.string.progress_outcome_succeeded
        RunOutcome.FAILED -> R.string.progress_outcome_failed
        RunOutcome.CANCELLED -> R.string.progress_outcome_cancelled
        RunOutcome.BUDGET_EXHAUSTED -> R.string.progress_outcome_budget
        RunOutcome.BREAKER_STOPPED -> R.string.progress_outcome_breaker
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
        RunStatus.FAILED, RunStatus.BUDGET_EXHAUSTED, RunStatus.BREAKER_STOPPED, RunStatus.BLOCKED -> StatusTone.BLOCKED
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
        RunStatus.BREAKER_STOPPED -> R.string.panel_status_breaker
        RunStatus.CANCELLED -> R.string.panel_status_cancelled
        RunStatus.EXPIRED, RunStatus.INTERRUPTED -> R.string.panel_status_stopped
        RunStatus.BLOCKED -> R.string.panel_status_blocked
    }
