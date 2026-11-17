/**
 * 悬浮球圆弧菜单纯逻辑（docs/tech/08 §5.1，N182）：菜单项、运行中会话的状态与排序、按钮摆位。
 * 按钮沿圆弧环绕悬浮球摆放（开口朝屏幕内侧）。独立于 Compose，供单测使用。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.agent.PauseReason
import com.example.autop.core.common.ThreadId
import com.example.autop.core.ui.component.ballface.BallFaceDirector
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 圆弧菜单动作（docs/tech/08 §5.1，N182）。 */
public enum class BallMenuAction {
    /** 运行中的会话列表（N225）：常驻；无会话时灰显，有会话时显示角标与小脸。点开后在菜单内展示列表。 */
    LIST,

    /** 录制流程：展开录制面板（flows-recording-recovery.md V5）。 */
    RECORD,

    /** 即时创建任务（新建会话）：展开输入面板。 */
    CREATE_TASK,

    /** 打开应用。 */
    OPEN_APP,

    /** 查看任务进度（有任务运行时）：打开进度悬浮窗。 */
    @Deprecated("执行中交互已迁至顶部控制条＋消息条，悬浮球不再提供运行交互（N218）")
    VIEW_PROGRESS,

    /** 中止当前任务（有任务运行时）。 */
    @Deprecated("执行中交互已迁至顶部控制条＋消息条，悬浮球不再提供运行交互（N218）")
    ABORT_RUN,

    /** 工具测试面板（两种状态都有；N198）。 */
    TOOL_TEST,
}

/**
 * 菜单项（N225）：列表始终在首位（运行中与空闲都有，空闲时灰显）；其后是新建会话、打开应用，
 * 再按开关追加录制（流程 Beta）与工具测试（调试）。顺序即圆弧上的摆位（从上到下）。
 *
 * @param toolTestEnabled 调试模式开关（`ui.debug.verboseLogging`）：关闭时不放 TOOL_TEST（tool_optimize D20）。
 * @param flowsEnabled 流程 Beta 开关（`features.flows`，docs/adr/0030）：关闭时不放 RECORD（录制流程）。
 * @param running 是否有未收口的会话。
 * @param showActionsWhileRunning `ui.appearance.petMenuActionsWhileRunning`：false 时运行中不放“新建会话”“打开应用”。
 */
public fun deriveBallMenu(
    toolTestEnabled: Boolean,
    flowsEnabled: Boolean,
    running: Boolean = false,
    showActionsWhileRunning: Boolean = true,
): List<BallMenuAction> {
    val showActions = showActionsWhileRunning || !running
    return listOfNotNull(
        BallMenuAction.LIST,
        BallMenuAction.CREATE_TASK.takeIf { showActions },
        BallMenuAction.OPEN_APP.takeIf { showActions },
        BallMenuAction.RECORD.takeIf { flowsEnabled },
        BallMenuAction.TOOL_TEST.takeIf { toolTestEnabled },
    )
}

/** 运行中会话的菜单状态（决定小脸与排序）。 */
public enum class MenuSessionStatus {
    /** 等待你的回答（最紧急）。 */
    WAITING,

    /** 被策略拦截（需要你决定）。 */
    BLOCKED,

    /** 已暂停。 */
    PAUSED,

    /** 运行中。 */
    RUNNING,
}

/**
 * 会话列表的一行。
 *
 * @property title 线程标题；无标题时为 null（界面显示默认文案）。
 * @property seed 小脸种子（与悬浮球同一事件口径：同一 Run 得到同一张脸）。
 */
public data class MenuSession(
    val threadId: ThreadId,
    val title: String?,
    val status: MenuSessionStatus,
    val seed: Long,
) {
    /** 会话小脸的表情（运行中取运行脸，其余取对应固定态）。 */
    val mood: BallFaceMood
        get() =
            when (status) {
                MenuSessionStatus.WAITING -> BallFaceMood.WAITING
                MenuSessionStatus.BLOCKED -> BallFaceMood.BLOCKED
                MenuSessionStatus.PAUSED -> BallFaceMood.PAUSED
                MenuSessionStatus.RUNNING -> BallFaceMood.RUNNING
            }
}

/** Run 记录 → 菜单状态：拦截优先，其次等待回答，再暂停，其余为运行中。 */
public fun menuSessionStatusOf(run: RunRecord): MenuSessionStatus =
    when {
        run.pauseReason == PauseReason.POLICY_BLOCKED -> MenuSessionStatus.BLOCKED
        run.waitingQuestion != null ||
            run.status == RunStatus.WAITING_USER ||
            run.status == RunStatus.WAITING_SCREEN -> MenuSessionStatus.WAITING
        run.status == RunStatus.PAUSED -> MenuSessionStatus.PAUSED
        else -> MenuSessionStatus.RUNNING
    }

/**
 * 运行中会话列表：按紧急程度排序（等待回答 > 拦截 > 暂停 > 运行中），同级保持输入顺序。
 * [titleOf] 取线程标题（`ThreadStore.titleOf`）。
 */
public suspend fun menuSessionsOf(
    openRuns: Map<ThreadId, RunRecord>,
    titleOf: suspend (ThreadId) -> String?,
): List<MenuSession> =
    openRuns
        .map { (threadId, run) ->
            MenuSession(
                threadId = threadId,
                title = titleOf(threadId),
                status = menuSessionStatusOf(run),
                seed = BallFaceDirector.seedOfEvent(run.id.value, 0),
            )
        }.sortedBy { it.status.ordinal }

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
    // 按钮间距下限：步距 ≥ [MIN_STEP_DEG] 弦距才不重叠（R=2.2 球时弦距 ≈ 59dp > 48dp 钮）；
    // 总扇面钳制在 [SPREAD_MAX_DEG] 内，防止球贴顶/贴底时按钮绕到背后。
    val stepDeg = if (actions.size > 1) min(MIN_STEP_DEG, SPREAD_MAX_DEG / (actions.size - 1)) else 0f
    val spreadDeg = stepDeg * (actions.size - 1)
    val halfWidth = itemWidthPx / 2
    val top = buttonSizePx / 2
    val bottom = screenHeightPx - itemHeightPx + buttonSizePx / 2
    return actions.mapIndexed { index, action ->
        // 扇面从上缘向下均分（两侧一致）；单按钮居中水平。
        val offsetDeg = if (actions.size > 1) spreadDeg / 2 - stepDeg * index else 0f
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

/** 相邻按钮最小步距（度）：R=2.2 球半径下弦距 ≈ 59dp，两个 48dp 钮不重叠。 */
private const val MIN_STEP_DEG: Float = 30f

/** 扇面上限（度）：按钮再多也不绕到球背后。 */
private const val SPREAD_MAX_DEG: Float = 120f

/** 半圆角度（朝左开口的基准角）。 */
private const val HALF_CIRCLE_DEG: Float = 180f
