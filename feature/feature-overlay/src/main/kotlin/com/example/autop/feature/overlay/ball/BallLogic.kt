/**
 * 悬浮球纯逻辑（docs/tech/08 §5.1）：状态推导、工具类别映射与停靠几何。
 * 独立于 Compose，供 [BallStateHolder] 与单测使用。
 *
 * 停靠几何（球左上角坐标系）：自由态停哪是哪；松手近左右边缘磁吸成半贴边态
 * （约 [DOCK_VISIBLE_RATIO] 露在屏内）；静置自动收成隐身态细条（球几乎全出屏，
 * 窗口内细条贴屏幕缘）。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.agent.RunState
import com.example.autop.core.ui.component.ballface.BallFaceDirector
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import kotlin.math.roundToInt

/**
 * 由全部未收口会话、活跃 Run 与无障碍连接状态推导球状态（N226）。
 *
 * 优先级：无障碍断开 > 录制（仅无会话时）> 拦截 > 等待回答 > 运行中 > 已暂停 > 空闲。
 * 任一会话被拦截或等待你的回答时，球以该状态为准；其它会话的进展不再左右表情。
 * 运行中的数字（会话数）由 [BallUiState.sessionCount] 给出，这里只决定状态。
 */
public fun deriveBallMode(
    active: ActiveRunInfo?,
    openRuns: Collection<RunRecord>,
    accessibilityConnected: Boolean,
    toolCategory: ToolCategory?,
    recording: Boolean = false,
): BallMode {
    val statuses = openRuns.map(::menuSessionStatusOf).toSet()
    return when {
        !accessibilityConnected -> BallMode.AccessibilityLost
        openRuns.isEmpty() -> if (recording) BallMode.Recording else BallMode.Idle
        MenuSessionStatus.BLOCKED in statuses -> BallMode.Blocked
        MenuSessionStatus.WAITING in statuses -> BallMode.WaitingAnswer
        MenuSessionStatus.RUNNING in statuses -> runningMode(active, openRuns, toolCategory)
        else -> BallMode.Paused
    }
}

/**
 * 运行中：活跃会话正在运行时取它的执行阶段与工具类别（单会话语义）；否则任取一个运行中的会话，
 * 阶段按思考处理。
 */
private fun runningMode(
    active: ActiveRunInfo?,
    openRuns: Collection<RunRecord>,
    toolCategory: ToolCategory?,
): BallMode.Running {
    val focus = active?.takeIf { menuSessionStatusOf(it.run) == MenuSessionStatus.RUNNING }
    if (focus != null) {
        return BallMode.Running(
            stepCount = focus.stepCount,
            toolCategory = toolCategory,
            state = focus.state,
            seed = BallFaceDirector.seedOfEvent(focus.run.id.value, focus.stepCount),
        )
    }
    val run = openRuns.first { menuSessionStatusOf(it) == MenuSessionStatus.RUNNING }
    return BallMode.Running(
        stepCount = 0,
        toolCategory = null,
        state = RunState.SAMPLING,
        seed = BallFaceDirector.seedOfEvent(run.id.value, 0),
    )
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
 * 松手判定：球心距左右边缘 ≤ [magnetPx] → 磁吸成半贴边态（[BallDock.EDGE]）；
 * 否则自由停留（[BallDock.NONE]，坐标钳回屏内）——位置由用户定，不强制吸边。
 */
public fun releasePosition(
    x: Int,
    y: Int,
    ballSizePx: Int,
    screenWidthPx: Int,
    magnetPx: Int,
): BallPosition {
    val centerX = x + ballSizePx / 2
    return when {
        centerX <= magnetPx -> docked(BallSide.LEFT, y, ballSizePx, screenWidthPx)
        centerX >= screenWidthPx - magnetPx -> docked(BallSide.RIGHT, y, ballSizePx, screenWidthPx)
        else ->
            BallPosition(
                x = x.coerceIn(0, maxOf(0, screenWidthPx - ballSizePx)),
                y = y,
                side = BallSide.NONE,
                dock = BallDock.NONE,
            )
    }
}

/** 半贴边态（约 [DOCK_VISIBLE_RATIO] 露在屏内）。 */
public fun docked(
    side: BallSide,
    y: Int,
    ballSizePx: Int,
    screenWidthPx: Int,
): BallPosition = BallPosition(dockX(side, ballSizePx, screenWidthPx), y, side, BallDock.EDGE)

/** 半贴边态的球 x：露出 [DOCK_VISIBLE_RATIO]。 */
public fun dockX(
    side: BallSide,
    ballSizePx: Int,
    screenWidthPx: Int,
): Int {
    val visible = (ballSizePx * DOCK_VISIBLE_RATIO).roundToInt().coerceIn(1, ballSizePx)
    return when (side) {
        BallSide.LEFT -> visible - ballSizePx
        BallSide.RIGHT -> screenWidthPx - visible
        BallSide.NONE -> 0
    }
}

/** 隐身态（细条）的球 x：球几乎全出屏，细条贴屏幕缘（[sliverPx] 为细条可见宽）。 */
public fun sliverX(
    side: BallSide,
    ballSizePx: Int,
    screenWidthPx: Int,
    sliverPx: Int,
): Int =
    when (side) {
        BallSide.LEFT -> sliverPx - ballSizePx
        BallSide.RIGHT -> screenWidthPx - sliverPx
        BallSide.NONE -> 0
    }

/** 半贴边态 → 隐身态细条（保持侧与高度）。 */
public fun collapseToSliver(
    position: BallPosition,
    ballSizePx: Int,
    screenWidthPx: Int,
    sliverPx: Int,
): BallPosition {
    if (position.dock != BallDock.EDGE || position.side == BallSide.NONE) return position
    return position.copy(x = sliverX(position.side, ballSizePx, screenWidthPx, sliverPx), dock = BallDock.SLIVER)
}

/**
 * 唤出（细条/半贴边 → 自由态）：球整体回到屏内（保持所在侧锚定，
 * 旋转后仍贴原来那侧），停哪由用户再拖。
 */
public fun revealPosition(
    position: BallPosition,
    screenWidthPx: Int,
    ballSizePx: Int,
): BallPosition =
    when (position.side) {
        BallSide.LEFT -> position.copy(x = 0, dock = BallDock.NONE)
        BallSide.RIGHT -> position.copy(x = (screenWidthPx - ballSizePx).coerceAtLeast(0), dock = BallDock.NONE)
        BallSide.NONE ->
            position.copy(
                x = position.x.coerceIn(0, (screenWidthPx - ballSizePx).coerceAtLeast(0)),
                dock = BallDock.NONE,
            )
    }

/** 半贴边态露出比例（球宽的 35% 在屏内，好抓取）。 */
public const val DOCK_VISIBLE_RATIO: Double = 0.35

private val TEXT_TOOLS = setOf("tool.input_text", "tool.clipboard_get", "tool.clipboard_set")
private val VISION_TOOLS = setOf("tool.screenshot", "tool.observe")
private val APP_TOOLS = setOf("tool.open_app", "tool.intent", "tool.list_apps")
private val GESTURE_TOOLS =
    setOf("tool.tap", "tool.tap_node", "tool.scroll", "tool.scroll_node", "tool.swipe", "tool.global_action")
