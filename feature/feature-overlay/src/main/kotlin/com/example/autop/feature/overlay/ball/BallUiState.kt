/**
 * 悬浮球 UI 状态（docs/tech/08 §5.1 的状态集合）：由 `RunService.observeActive`、
 * 无障碍连接状态与配置推导（[deriveBallMode]）。球体本身只表达表情，不携带进度。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.runtime.Immutable
import com.example.autop.agent.RunState
import com.example.autop.core.ui.component.ballface.BallFaceVariant

/** 悬浮球状态（docs/tech/08 §5.1）。 */
public sealed interface BallMode {
    /** 空闲：单击展开圆弧菜单（即时创建 / 打开应用，N182）。 */
    public data object Idle : BallMode

    /**
     * 运行中：表演态表情（工作中卫星绕行）；双击可停止。
     *
     * @property state Run 的执行阶段（起跑/思考/工具/整理），决定表情池。
     * @property toolCategory 当前工具类别，只在 [RunState.EXECUTING_TOOLS] 时参与选池。
     * @property seed 事件种子（[com.example.autop.core.ui.component.ballface.BallFaceDirector.seedOfEvent]）。
     */
    public data class Running(
        val stepCount: Int,
        val toolCategory: ToolCategory?,
        val state: RunState,
        val seed: Long,
    ) : BallMode

    /** 等待回答：单击展开圆弧菜单（查看进度 / 中止，N182）。 */
    public data object WaitingAnswer : BallMode

    /** 已暂停：单击展开圆弧菜单（查看进度 / 中止，N182）。 */
    public data object Paused : BallMode

    /** 被拦截（策略拒绝）：单击展开圆弧菜单（查看进度 / 中止，N182）。 */
    public data object Blocked : BallMode

    /** 无障碍已断开（仅 application 通道下球仍可见时）：单击跳系统无障碍设置。 */
    public data object AccessibilityLost : BallMode

    /** 流程录制中：单击展开圆弧菜单（录制面板在菜单中打开）。 */
    public data object Recording : BallMode
}

/** 当前工具类别（决定运行中表情池与卫星符号）。 */
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
    /** 动效开关（`ui.appearance.motionEnabled`，docs/tech/08 §6）。 */
    val motionEnabled: Boolean = true,
    /** 球体形态（`ui.appearance.ballShape`）。 */
    val ballShape: BallFaceVariant = BallFaceVariant.SOLID,
    /** 悬浮球直径（dp，`ui.overlay.ballSizeDp`）。 */
    val ballSizeDp: Int = DEFAULT_BALL_SIZE_DP,
    /** 双击停止开关（`ui.overlay.doubleTapStops`）。 */
    val doubleTapStops: Boolean = true,
    /** 未收口会话数（运行中 + 等待 + 暂停）：运行中显示在卫星上，其它状态显示在角标上。 */
    val sessionCount: Int = 0,
    /** 球体与贴边细条透明度（`ui.overlay.opacity`，1.0=完全不透明）。 */
    val opacity: Float = 1f,
)
