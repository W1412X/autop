/**
 * 悬浮球 UI 状态（docs/tech/08 §5.1 的六态）：由 `RunService.observeActive`、
 * 无障碍连接状态与配置推导（[deriveBallMode]）。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.runtime.Immutable

/** 悬浮球状态（docs/tech/08 §5.1）。 */
public sealed interface BallMode {
    /** 空闲：单击展开圆弧菜单（即时创建 / 打开应用，N182）。 */
    public data object Idle : BallMode

    /** 运行中：进度环 + 当前工具类别图标；双击可停止。 */
    public data class Running(
        val stepCount: Int,
        /** 步数 / 预算（`agent.budget.maxSteps`）；预算非正时为 null（画不定进度圈）。 */
        val progress: Float?,
        val toolCategory: ToolCategory?,
    ) : BallMode

    /** 等待回答：单击展开圆弧菜单（查看进度 / 中止，N182）。 */
    public data object WaitingAnswer : BallMode

    /** 已暂停：单击展开圆弧菜单（查看进度 / 中止，N182）。 */
    public data object Paused : BallMode

    /** 被拦截（策略拒绝）：单击展开圆弧菜单（查看进度 / 中止，N182）。 */
    public data object Blocked : BallMode

    /** 无障碍已断开（仅 application 通道下球仍可见时）：单击跳系统无障碍设置。 */
    public data object AccessibilityLost : BallMode
}

/** 当前工具类别（悬浮球中心图标，docs/tech/08 §5.1"中心图标换成当前工具类别图标"）。 */
public enum class ToolCategory {
    /** 点按、滑动、手势类工具。 */
    GESTURE,

    /** 文本输入、剪贴板类工具。 */
    TEXT,

    /** 截图、观察、节点读取类工具。 */
    VISION,

    /** 应用/URI 启动类工具。 */
    APP,

    /** 其余工具（含尚无工具调用时）。 */
    OTHER,
}

/** 悬浮球渲染状态。 */
@Immutable
public data class BallUiState(
    val mode: BallMode = BallMode.Idle,
    /** 呼吸光晕周期（`ui.overlay.breathMs`）。 */
    val breathMs: Long = 1600,
    /** 动效开关（`ui.appearance.motionEnabled`，docs/tech/08 §6）。 */
    val motionEnabled: Boolean = true,
    /** 双击停止开关（`ui.overlay.doubleTapStops`）。 */
    val doubleTapStops: Boolean = true,
    /** 贴边隐藏比例（`ui.overlay.edgeHideRatio`，N217 默认收边只露一小点）。 */
    val edgeHideRatio: Double = 0.8,
    /** 输入面板"针对当前屏幕"默认值（`ui.overlay.attachScreenDefault`）。 */
    val attachScreenDefault: Boolean = true,
)
