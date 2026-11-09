/**
 * 顶部运行控制条的状态映射（纯函数，BallVisualSpec 同款模式）：标题与按钮组只由 Run 状态
 * 决定——运行中[暂停][中止]、暂停/拦截[继续][中止]、等待回答/排队[中止]（停止是唯一安全
 * 出口，始终在）。显示与否（有活跃 Run 才显示）在 IndicatorController。
 */
package com.example.autop.feature.overlay.indicator

import com.example.autop.agent.PauseReason
import com.example.autop.feature.overlay.progress.flowHintOf
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** 控制条按钮（动作执行在 PanelRunActions；暂停/继续同一切换入口）。 */
internal enum class RunBarAction {
    /** 暂停（运行中显示）。 */
    PAUSE,

    /** 继续（暂停/拦截时显示）。 */
    RESUME,

    /** 中止（先强制解除触摸盾再取消，N191/N217）。 */
    STOP,
}

/** 控制条状态提示。 */
internal sealed interface RunBarHint {
    /** 运行中：标题（流程提示优先，回落当前操作）+ 步数。 */
    data class Running(
        val title: String?,
        val stepCount: Int,
    ) : RunBarHint

    /** 已暂停。 */
    data object Paused : RunBarHint

    /** 被安全规则拦截（等待用户决定）。 */
    data object Blocked : RunBarHint

    /** 等待回答（ask_user）。 */
    data object Waiting : RunBarHint

    /** 排队中。 */
    data object Queued : RunBarHint
}

/** 控制条渲染状态。 */
internal data class RunBarSpec(
    val hint: RunBarHint,
    val buttons: List<RunBarAction>,
)

/** 由活跃 Run 推导控制条状态。 */
internal fun runBarSpecOf(active: ActiveRunInfo): RunBarSpec {
    val run = active.run
    val hint =
        when {
            run.pauseReason == PauseReason.POLICY_BLOCKED -> RunBarHint.Blocked
            run.waitingQuestion != null -> RunBarHint.Waiting
            run.status == RunStatus.PAUSED -> RunBarHint.Paused
            run.status == RunStatus.QUEUED -> RunBarHint.Queued
            else -> RunBarHint.Running(title = flowHintOf(active) ?: active.currentStep, stepCount = active.stepCount)
        }
    val buttons =
        when (hint) {
            is RunBarHint.Running -> listOf(RunBarAction.PAUSE, RunBarAction.STOP)
            RunBarHint.Paused, RunBarHint.Blocked -> listOf(RunBarAction.RESUME, RunBarAction.STOP)
            RunBarHint.Waiting, RunBarHint.Queued -> listOf(RunBarAction.STOP)
        }
    return RunBarSpec(hint = hint, buttons = buttons)
}
