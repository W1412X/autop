/**
 * 顶部运行控制条的状态映射（纯函数，BallVisualSpec 同款模式）：标题与按钮组只由 Run 状态
 * 决定——运行中[暂停][中止]、暂停/拦截[继续][中止]、等待回答/排队[中止]（停止是唯一安全
 * 出口，始终在）。显示与否（有活跃 Run 才显示）在 IndicatorController。
 */
package com.example.autop.feature.overlay.indicator

import com.example.autop.agent.PauseReason
import com.example.autop.core.ui.component.ballface.BallFaceDirector
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.example.autop.feature.overlay.ball.ballFaceSceneOf
import com.example.autop.feature.overlay.ball.categoryOfTool
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
    /**
     * 运行中：标题（[hintTitleOf] 变体文案）。不显示步数：没有真实总量，步数只是计数。
     * [scene] 与 [seed] 与悬浮球同源（同一事件得到同一张脸）。
     */
    data class Running(
        val title: String,
        val scene: BallFaceScene,
        val seed: Long,
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

/**
 * 由活跃 Run 推导控制条状态；[previousHint] / [roll] 供标题变体的加权反重复抽取
 * （[hintTitleOf]，同一展示段内稳定）。
 */
internal fun runBarSpecOf(
    active: ActiveRunInfo,
    previousHint: String? = null,
    roll: Float = 0f,
): RunBarSpec {
    val run = active.run
    val hint =
        when {
            run.pauseReason == PauseReason.POLICY_BLOCKED -> RunBarHint.Blocked
            run.waitingQuestion != null -> RunBarHint.Waiting
            run.status == RunStatus.PAUSED -> RunBarHint.Paused
            run.status == RunStatus.QUEUED -> RunBarHint.Queued
            else ->
                RunBarHint.Running(
                    title = hintTitleOf(active, previousHint, roll),
                    scene = ballFaceSceneOf(active.state, active.toolId?.let(::categoryOfTool)),
                    seed = BallFaceDirector.seedOfEvent(active.run.id.value, active.stepCount),
                )
        }
    val buttons =
        when (hint) {
            is RunBarHint.Running -> listOf(RunBarAction.PAUSE, RunBarAction.STOP)
            RunBarHint.Paused, RunBarHint.Blocked -> listOf(RunBarAction.RESUME, RunBarAction.STOP)
            RunBarHint.Waiting, RunBarHint.Queued -> listOf(RunBarAction.STOP)
        }
    return RunBarSpec(hint = hint, buttons = buttons)
}

/** 控制条前导表情：与悬浮球同一套状态 → 表情口径（P1-8）。 */
internal fun runBarFaceOf(hint: RunBarHint): BallFaceMood =
    when (hint) {
        is RunBarHint.Running -> BallFaceMood.RUNNING
        RunBarHint.Paused -> BallFaceMood.PAUSED
        RunBarHint.Blocked -> BallFaceMood.BLOCKED
        RunBarHint.Waiting -> BallFaceMood.WAITING
        RunBarHint.Queued -> BallFaceMood.IDLE
    }
