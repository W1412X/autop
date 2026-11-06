/**
 * 悬浮球视觉参数（docs/tech/08 §5.1，N185）：各状态的环、光晕、角标与图标差异由
 * [ballVisualSpec] 纯函数推导（单测锁定差异矩阵），渲染与动效见 [BallVisual]。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.runtime.Immutable
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R

/** 外圈环形态。 */
public enum class RingKind {
    /** 无环（空闲）。 */
    NONE,

    /** 进度环（步数 / 预算，随步数增长）。 */
    PROGRESS,

    /** 不定进度环（预算未知的缺口弧，动效开启时旋转）。 */
    INDETERMINATE,

    /** 整圈状态环（非运行态）。 */
    FULL,
}

/** 光晕形态（呼吸/脉冲属动效，动效关闭时一律无光晕）。 */
public enum class HaloKind {
    /** 无光晕。 */
    NONE,

    /** 呼吸光晕（运行中，alpha 起伏）。 */
    BREATH,

    /** 脉冲光晕（等待回答的提醒感）。 */
    PULSE,
}

/** 右上角角标形态。 */
public enum class BadgeKind {
    /** 无角标。 */
    NONE,

    /** 静态圆点（动效关闭时的等待提醒）。 */
    DOT,

    /** 脉冲圆点（等待回答，缩放+透明度起伏）。 */
    PULSE,
}

/** 悬浮球视觉参数（N185 状态差异化）。 */
@Immutable
public data class BallVisualSpec(
    /** 外圈环语义色键（null = 无环）。 */
    val ringTone: StatusTone?,
    /** 外圈环形态。 */
    val ringKind: RingKind,
    /** 进度环比例（仅 [RingKind.PROGRESS]）。 */
    val ringProgress: Float?,
    /** 光晕形态。 */
    val haloKind: HaloKind,
    /** 角标形态。 */
    val badgeKind: BadgeKind,
    /** 中心图标透明度（无障碍断开时弱化）。 */
    val iconAlpha: Float,
)

/** 无障碍断开时中心图标的弱化透明度。 */
private const val ICON_ALPHA_DIM: Float = 0.55f

/** 图标常态透明度。 */
private const val ICON_ALPHA_FULL: Float = 1f

/**
 * 状态 → 视觉参数（docs/tech/08 §5.1 表，N185）：
 * 空闲无环无光晕；运行中进度环（预算未知为不定环）+ 呼吸光晕；
 * 等待回答整圈等待环 + 脉冲角标（动效关闭时静态圆点，提醒不消失）；
 * 暂停 / 被拦截整圈状态环；无障碍断开整圈错误环 + 图标弱化。
 *
 * @param motionEnabled 动效是否开启（`ui.appearance.motionEnabled` ∧ `LocalMotionEnabled`）。
 */
@Suppress("LongMethod") // 六态映射表：一个状态一个分支，拆开反而难读
public fun ballVisualSpec(
    mode: BallMode,
    motionEnabled: Boolean,
): BallVisualSpec =
    when (mode) {
        BallMode.Idle ->
            BallVisualSpec(
                ringTone = null,
                ringKind = RingKind.NONE,
                ringProgress = null,
                haloKind = HaloKind.NONE,
                badgeKind = BadgeKind.NONE,
                iconAlpha = ICON_ALPHA_FULL,
            )
        BallMode.Recording ->
            BallVisualSpec(
                ringTone = StatusTone.WAITING,
                ringKind = RingKind.FULL,
                ringProgress = null,
                haloKind = HaloKind.NONE,
                badgeKind = BadgeKind.NONE,
                iconAlpha = ICON_ALPHA_FULL,
            )
        is BallMode.Running ->
            BallVisualSpec(
                ringTone = StatusTone.RUNNING,
                ringKind = if (mode.progress == null) RingKind.INDETERMINATE else RingKind.PROGRESS,
                ringProgress = mode.progress,
                haloKind = if (motionEnabled) HaloKind.BREATH else HaloKind.NONE,
                badgeKind = BadgeKind.NONE,
                iconAlpha = ICON_ALPHA_FULL,
            )
        BallMode.WaitingAnswer ->
            BallVisualSpec(
                ringTone = StatusTone.WAITING,
                ringKind = RingKind.FULL,
                ringProgress = null,
                haloKind = if (motionEnabled) HaloKind.PULSE else HaloKind.NONE,
                badgeKind = if (motionEnabled) BadgeKind.PULSE else BadgeKind.DOT,
                iconAlpha = ICON_ALPHA_FULL,
            )
        BallMode.Paused ->
            BallVisualSpec(
                ringTone = StatusTone.PAUSED,
                ringKind = RingKind.FULL,
                ringProgress = null,
                haloKind = HaloKind.NONE,
                badgeKind = BadgeKind.NONE,
                iconAlpha = ICON_ALPHA_FULL,
            )
        BallMode.Blocked ->
            BallVisualSpec(
                ringTone = StatusTone.BLOCKED,
                ringKind = RingKind.FULL,
                ringProgress = null,
                haloKind = HaloKind.NONE,
                badgeKind = BadgeKind.NONE,
                iconAlpha = ICON_ALPHA_FULL,
            )
        BallMode.AccessibilityLost ->
            BallVisualSpec(
                ringTone = StatusTone.BLOCKED,
                ringKind = RingKind.FULL,
                ringProgress = null,
                haloKind = HaloKind.NONE,
                badgeKind = BadgeKind.NONE,
                iconAlpha = ICON_ALPHA_DIM,
            )
    }

/**
 * 球的无障碍描述文案（strings `overlay_ball_*_description`，N185 接线）：
 * 状态变化时描述同步变化（不只靠颜色区分）。
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
