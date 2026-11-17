/**
 * 进度悬浮窗的状态推导（docs/tech/08 §5.3，N182/N185）：头部状态规格与结束元信息的查找。
 * 消息流的归并与渲染已交给对话页同一套组件（ADR-0039，`mergeTimeline` + `TimelineRow`），此处不再映射行。
 * 独立于 Compose，供单测使用。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.runtime.Immutable
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.core.thread.ui.TimelineEntry
import com.example.autop.core.thread.ui.TimelineItem
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** RunOutcome → 结束状态徽标文案（strings `progress_outcome_*`）。 */
public fun outcomeTextOf(outcome: RunOutcome): Int =
    when (outcome) {
        RunOutcome.SUCCEEDED -> R.string.progress_outcome_succeeded
        RunOutcome.FAILED -> R.string.progress_outcome_failed
        RunOutcome.CANCELLED -> R.string.progress_outcome_cancelled
        RunOutcome.BUDGET_EXHAUSTED -> R.string.progress_outcome_budget
        RunOutcome.BREAKER_STOPPED -> R.string.progress_outcome_breaker
        RunOutcome.INTERRUPTED -> R.string.progress_outcome_interrupted
        RunOutcome.EXPIRED -> R.string.progress_outcome_expired
    }

/** RunOutcome → 结束状态的语义色。 */
internal fun outcomeToneOf(outcome: RunOutcome): StatusTone =
    when (outcome) {
        RunOutcome.SUCCEEDED -> StatusTone.SUCCEEDED
        RunOutcome.FAILED -> StatusTone.BLOCKED
        RunOutcome.CANCELLED -> StatusTone.NEUTRAL
        RunOutcome.BUDGET_EXHAUSTED -> StatusTone.PAUSED
        RunOutcome.BREAKER_STOPPED -> StatusTone.BLOCKED
        RunOutcome.INTERRUPTED -> StatusTone.PAUSED
        RunOutcome.EXPIRED -> StatusTone.NEUTRAL
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
    finished: TimelineItem.RunMeta?,
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
                tone = outcomeToneOf(finished.outcome),
                chipText = outcomeTextOf(finished.outcome),
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

/**
 * 头部表情：运行中按暂停/等待/拦截优先，结束后按结果，未开始为空闲。
 * 与 [progressHeaderSpec] 同源，避免色条和表情说两种话。
 */
internal fun progressFaceMoodOf(
    active: ActiveRunInfo?,
    finished: TimelineItem.RunMeta?,
): BallFaceMood =
    when {
        active != null -> {
            val status = active.run.status
            when {
                active.run.pauseReason == PauseReason.POLICY_BLOCKED -> BallFaceMood.BLOCKED
                active.run.waitingQuestion != null -> BallFaceMood.WAITING
                status == RunStatus.PAUSED -> BallFaceMood.PAUSED
                status == RunStatus.WAITING_USER || status == RunStatus.WAITING_SCREEN -> BallFaceMood.WAITING
                status == RunStatus.RUNNING -> BallFaceMood.RUNNING
                status == RunStatus.SUCCEEDED -> BallFaceMood.SUCCESS
                status in failureStatuses -> BallFaceMood.FAILURE
                else -> BallFaceMood.IDLE
            }
        }
        finished != null -> moodOfOutcome(finished.outcome)
        else -> BallFaceMood.IDLE
    }

private val failureStatuses =
    setOf(
        RunStatus.FAILED,
        RunStatus.BUDGET_EXHAUSTED,
        RunStatus.BREAKER_STOPPED,
        RunStatus.BLOCKED,
    )

private fun moodOfOutcome(outcome: RunOutcome): BallFaceMood =
    when (outcome) {
        RunOutcome.SUCCEEDED -> BallFaceMood.SUCCESS
        RunOutcome.FAILED, RunOutcome.BREAKER_STOPPED -> BallFaceMood.FAILURE
        RunOutcome.BUDGET_EXHAUSTED, RunOutcome.INTERRUPTED -> BallFaceMood.PAUSED
        RunOutcome.CANCELLED, RunOutcome.EXPIRED -> BallFaceMood.IDLE
    }

/**
 * 最近一次结束的元信息：优先取末尾的独立结束行，其次取附着在助手文本上的元信息（对话页同口径）。
 * 没有则为 null（Run 尚未结束）。
 */
public fun finishedMetaOf(entries: List<TimelineEntry>): TimelineItem.RunMeta? =
    entries.asReversed().firstNotNullOfOrNull { entry ->
        when (val item = entry.item) {
            is TimelineItem.RunMeta -> item
            is TimelineItem.AssistantText -> item.meta
            else -> null
        }
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
