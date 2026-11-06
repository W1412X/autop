/**
 * 悬浮球纯逻辑（docs/tech/08 §5.1）：状态推导、工具类别映射与贴边吸附计算。
 * 独立于 Compose，供 [BallStateHolder] 与单测使用。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.agent.PauseReason
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus
import kotlin.math.roundToInt

/**
 * 由活跃 Run、无障碍连接状态推导球状态（docs/tech/08 §5.1 表）。
 *
 * 优先级：无障碍断开（球仍在 application 通道可见时的提醒）> 被拦截 >
 * 等待回答 > 已暂停 > 运行中 > 空闲。
 *
 * @param maxSteps 步数预算（`agent.budget.maxSteps`），非正时进度为 null。
 */
public fun deriveBallMode(
    active: ActiveRunInfo?,
    accessibilityConnected: Boolean,
    maxSteps: Int,
    toolCategory: ToolCategory?,
    recording: Boolean = false,
): BallMode {
    val run = active?.run
    return when {
        !accessibilityConnected -> BallMode.AccessibilityLost
        recording && run == null -> BallMode.Recording
        run == null -> BallMode.Idle
        run.pauseReason == PauseReason.POLICY_BLOCKED -> BallMode.Blocked
        run.waitingQuestion != null -> BallMode.WaitingAnswer
        run.status == RunStatus.PAUSED -> BallMode.Paused
        else -> BallMode.Running(active.stepCount, progressOf(active.stepCount, maxSteps), toolCategory)
    }
}

/** 步数进度（0f..1f）；预算非正返回 null。 */
public fun progressOf(
    stepCount: Int,
    maxSteps: Int,
): Float? =
    if (maxSteps <= 0) {
        null
    } else {
        (stepCount.toFloat() / maxSteps).coerceIn(0f, 1f)
    }

/** 工具 id → 类别（tool.* 前缀按动作语义归类；docs/tech/08 §5.1）。 */
public fun categoryOfTool(toolName: String): ToolCategory =
    when {
        toolName in TEXT_TOOLS -> ToolCategory.TEXT
        toolName in VISION_TOOLS -> ToolCategory.VISION
        toolName in APP_TOOLS -> ToolCategory.APP
        toolName in GESTURE_TOOLS -> ToolCategory.GESTURE
        else -> ToolCategory.OTHER
    }

/**
 * 贴边吸附：松手后取最近的左右边，按 [edgeHideRatio] 把球收到屏幕外只露一小点（N217），
 * 点按露头唤出（[revealPosition]）。
 *
 * @param x 拖动结束时球的左上角 x（px）。
 * @param y 拖动结束时球的左上角 y（px）。
 * @param ballSizePx 球边长（px）。
 * @param screenWidthPx 屏幕宽（px）。
 */
public fun snapTo(
    x: Float,
    y: Int,
    ballSizePx: Int,
    screenWidthPx: Int,
    edgeHideRatio: Double,
): BallPosition {
    val hide = hidePx(ballSizePx, edgeHideRatio)
    val centerX = x + ballSizePx / 2
    return if (centerX < screenWidthPx / 2) {
        BallPosition(x = -hide, y = y, side = BallSide.LEFT, hidden = true)
    } else {
        BallPosition(x = screenWidthPx - ballSizePx + hide, y = y, side = BallSide.RIGHT, hidden = true)
    }
}

/** 贴边隐藏态点按「全部唤出」（N217）：保持所在侧，球整体回到屏内并持久化。 */
public fun revealPosition(
    position: BallPosition,
    screenWidthPx: Int,
    ballSizePx: Int,
): BallPosition =
    when (position.side) {
        BallSide.LEFT -> position.copy(x = 0, hidden = false)
        BallSide.RIGHT -> position.copy(x = (screenWidthPx - ballSizePx).coerceAtLeast(0), hidden = false)
        BallSide.NONE ->
            position.copy(
                x = position.x.coerceIn(0, (screenWidthPx - ballSizePx).coerceAtLeast(0)),
                hidden = false,
            )
    }

/** 贴边隐藏量（px）：球宽 × 隐藏比例（钳制在 [MIN_HIDE_RATIO]..[MAX_HIDE_RATIO]）。 */
public fun hidePx(
    ballSizePx: Int,
    edgeHideRatio: Double,
): Int = (ballSizePx * edgeHideRatio.coerceIn(MIN_HIDE_RATIO, MAX_HIDE_RATIO)).roundToInt()

/** 贴边隐藏比例下限。 */
public const val MIN_HIDE_RATIO: Double = 0.0

/** 贴边隐藏比例上限（N217：露头只剩一小点，再少就点不到了）。 */
public const val MAX_HIDE_RATIO: Double = 0.85

private val TEXT_TOOLS = setOf("tool.input_text", "tool.clipboard_get", "tool.clipboard_set")
private val VISION_TOOLS = setOf("tool.screenshot", "tool.observe")
private val APP_TOOLS = setOf("tool.open_app", "tool.open_uri", "tool.list_apps")
private val GESTURE_TOOLS = setOf("tool.tap", "tool.scroll", "tool.swipe", "tool.global_action")
