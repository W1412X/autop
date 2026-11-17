/**
 * 悬浮球的表情映射（docs/tech/08 §5.1 + 设计稿 `ball-face-events.html`）：球状态 → 表情状态、
 * 表演场景与无障碍描述。颜色不承载状态，状态只由表情与卫星符号表达。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.agent.RunState
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.example.autop.feature.overlay.R

/** 球状态 → 表情状态（固定态一态一张脸；运行中按场景抽取）。 */
internal fun ballFaceMoodOf(mode: BallMode): BallFaceMood =
    when (mode) {
        BallMode.Idle -> BallFaceMood.IDLE
        is BallMode.Running -> if (mode.state == RunState.STARTING) BallFaceMood.RUNNING_START else BallFaceMood.RUNNING
        BallMode.WaitingAnswer -> BallFaceMood.WAITING
        BallMode.Paused -> BallFaceMood.PAUSED
        BallMode.Blocked -> BallFaceMood.BLOCKED
        BallMode.AccessibilityLost -> BallFaceMood.LOST
        BallMode.Recording -> BallFaceMood.RECORDING
    }

/** 运行中球的表演场景。 */
internal fun ballFaceSceneOf(mode: BallMode.Running): BallFaceScene = ballFaceSceneOf(mode.state, mode.toolCategory)

/**
 * 执行阶段 + 工具类别 → 表演场景（设计稿：起跑/思考/手势/文本/视觉/应用/整理）。
 * 工具类别未知（OTHER 或尚无工具）时按思考池处理。
 */
internal fun ballFaceSceneOf(
    state: RunState,
    category: ToolCategory?,
): BallFaceScene =
    when (state) {
        RunState.STARTING -> BallFaceScene.STARTING
        RunState.COMPACTING -> BallFaceScene.COMPACTING
        RunState.EXECUTING_TOOLS ->
            when (category) {
                ToolCategory.GESTURE -> BallFaceScene.GESTURE
                ToolCategory.TEXT -> BallFaceScene.TEXT
                ToolCategory.VISION -> BallFaceScene.VISION
                ToolCategory.APP -> BallFaceScene.APP
                ToolCategory.OTHER, null -> BallFaceScene.SAMPLING
            }
        else -> BallFaceScene.SAMPLING
    }

/**
 * 球的无障碍描述文案（strings `overlay_ball_*_description`，N185 接线）：
 * 状态变化时描述同步变化（不只靠表情区分）。
 */
public fun ballDescriptionOf(mode: BallMode): Int =
    when (mode) {
        BallMode.Idle -> R.string.overlay_ball_description
        is BallMode.Running -> R.string.overlay_ball_running_description
        BallMode.WaitingAnswer -> R.string.overlay_ball_waiting_description
        BallMode.Recording -> R.string.overlay_ball_recording_description
        BallMode.Paused -> R.string.overlay_ball_paused_description
        BallMode.Blocked -> R.string.overlay_ball_blocked_description
        BallMode.AccessibilityLost -> R.string.overlay_ball_accessibility_lost_description
    }

/**
 * 运行中的表演场景：多个会话同时运行时从全部表演脸中随机（[BallFaceScene.MIXED]）；
 * 只有一个会话时保持按执行阶段与工具类别分池（单会话语义不丢）。
 */
internal fun ballFaceSceneOf(
    mode: BallMode.Running,
    sessionCount: Int,
): BallFaceScene = if (sessionCount > 1) BallFaceScene.MIXED else ballFaceSceneOf(mode)
