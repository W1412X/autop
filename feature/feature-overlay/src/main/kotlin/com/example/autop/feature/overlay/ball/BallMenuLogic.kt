/**
 * 悬浮球圆弧菜单纯逻辑（docs/tech/08 §5.1，N182）：菜单项按运行态条件给出，
 * 按钮沿圆弧环绕悬浮球摆放（开口朝屏幕内侧）。独立于 Compose，供单测使用。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.task.ActiveRunInfo
import kotlin.math.cos
import kotlin.math.sin

/** 圆弧菜单动作（docs/tech/08 §5.1，N182）。 */
public enum class BallMenuAction {
    /** 即时创建任务（无任务运行时）：展开输入面板。 */
    CREATE_TASK,

    /** 打开应用（无任务运行时）。 */
    OPEN_APP,

    /** 查看任务进度（有任务运行时）：打开进度悬浮窗。 */
    VIEW_PROGRESS,

    /** 中止当前任务（有任务运行时）。 */
    ABORT_RUN,

    /** 工具测试面板（两种状态都有；N198）。 */
    TOOL_TEST,
}

/**
 * 菜单项条件（N182，N198 加工具测试）：有任务运行给「查看进度 / 中止 / 工具测试」，
 * 空闲给「即时创建 / 打开应用 / 工具测试」。顺序即圆弧上的摆位（开口朝屏幕内侧，从上到下）。
 */
public fun deriveBallMenu(active: ActiveRunInfo?): List<BallMenuAction> =
    if (active == null) {
        listOf(BallMenuAction.CREATE_TASK, BallMenuAction.OPEN_APP, BallMenuAction.TOOL_TEST)
    } else {
        listOf(BallMenuAction.VIEW_PROGRESS, BallMenuAction.ABORT_RUN, BallMenuAction.TOOL_TEST)
    }

/** 圆弧菜单按钮中心（px，屏幕绝对坐标）。 */
public data class MenuSpot(
    val action: BallMenuAction,
    val x: Float,
    val y: Float,
)

/**
 * 按钮沿圆弧环绕球心摆放（N182）：球心偏左时弧开口朝右、偏右时朝左，
 * 按钮均分在 [SPREAD_DEG] 扇面内（序号 0 在上、依次向下）。
 * N217：钳制按**整块按钮**（圆形图标 + 下方文字）的外接盒算——只按圆半径钳制时
 * 文字越出屏幕边缘被裁；球贴顶/贴底展开时按钮整体不越界。
 *
 * @param radiusPx 球心到按钮圆心的距离（px）。
 * @param buttonSizePx 按钮圆形直径（px；圆心即 [MenuSpot]，文字挂在圆下方）。
 * @param itemWidthPx 整块按钮宽（圆与文字的较宽者，px）。
 * @param itemHeightPx 整块按钮高（圆 + 间距 + 文字，px）。
 */
@Suppress("LongParameterList") // 纯几何函数：参数摊平传入比打包结构更直白（N182）
public fun arcSpots(
    actions: List<BallMenuAction>,
    ballCenterX: Float,
    ballCenterY: Float,
    radiusPx: Float,
    screenWidthPx: Float,
    screenHeightPx: Float,
    buttonSizePx: Float,
    itemWidthPx: Float,
    itemHeightPx: Float,
): List<MenuSpot> {
    val facingRight = ballCenterX * 2 < screenWidthPx
    val stepDeg = if (actions.size > 1) SPREAD_DEG / (actions.size - 1) else 0f
    val halfWidth = itemWidthPx / 2
    val top = buttonSizePx / 2
    val bottom = screenHeightPx - itemHeightPx + buttonSizePx / 2
    return actions.mapIndexed { index, action ->
        // 扇面从上缘向下均分（两侧一致）；单按钮居中水平。
        val offsetDeg = if (actions.size > 1) SPREAD_DEG / 2 - stepDeg * index else 0f
        val deg = if (facingRight) offsetDeg else HALF_CIRCLE_DEG - offsetDeg
        val rad = Math.toRadians(deg.toDouble())
        val x = ballCenterX + radiusPx * cos(rad).toFloat()
        val y = ballCenterY - radiusPx * sin(rad).toFloat()
        MenuSpot(
            action = action,
            x = x.coerceIn(halfWidth, (screenWidthPx - halfWidth).coerceAtLeast(halfWidth)),
            y = y.coerceIn(top, bottom.coerceAtLeast(top)),
        )
    }
}

/** 菜单扇面角（度）：两个按钮分列 ±35°。 */
private const val SPREAD_DEG: Float = 70f

/** 半圆角度（朝左开口的基准角）。 */
private const val HALF_CIRCLE_DEG: Float = 180f
