/**
 * 空闲态的环境表情与手势反应（设计预览 ambient-animations）：睡眠、充电、低电量，以及点按、长按、拖动、
 * 甩出、唤出、唤醒、晨间哈欠与招手。
 *
 * 规则：只在 [BallFaceMood.IDLE] 下生效，状态表情（等待、拦截、运行等）始终优先。
 * 卫星一律慢绕（周期不短于 30 秒，表演脸的 2.6 秒快转不在此列），不做快速旋转。
 */
package com.example.autop.core.ui.component.ballface

/** 睡眠、充电、低电量的表情；[BallAmbientMode.NONE] 返回 null（走空闲轮换池）。 */
public fun ambientExpressionOf(mode: BallAmbientMode): BallFaceExpression? =
    when (mode) {
        BallAmbientMode.NONE -> null
        BallAmbientMode.SLEEP -> SLEEP_EXPRESSION
        BallAmbientMode.CHARGING -> CHARGING_EXPRESSION
        BallAmbientMode.TIRED -> TIRED_EXPRESSION
    }

/** 手势反应的表情。 */
public fun reactionExpressionOf(reaction: BallReaction): BallFaceExpression =
    when (reaction) {
        BallReaction.TAP -> TAP_EXPRESSION
        BallReaction.LONG_PRESS -> LONG_PRESS_EXPRESSION
        BallReaction.DRAG -> DRAG_EXPRESSION
        BallReaction.FLING -> FLING_EXPRESSION
        BallReaction.REVEAL -> REVEAL_EXPRESSION
        BallReaction.WAKE -> WAKE_EXPRESSION
        BallReaction.YAWN -> YAWN_EXPRESSION
        BallReaction.GREET -> GREET_EXPRESSION
    }

/**
 * 空闲态的覆盖表情：手势反应优先，其次是环境状态。两者都没有时返回 null（走表演池的空闲轮换）。
 * 非空闲状态一律返回 null，状态表情不受影响。
 */
public fun idleOverrideOf(
    mood: BallFaceMood,
    ambient: BallAmbientMode,
    reaction: BallReaction?,
): BallFaceExpression? {
    if (mood != BallFaceMood.IDLE) return null
    return reaction?.let { reactionExpressionOf(it) } ?: ambientExpressionOf(ambient)
}

/** 卫星慢绕的周期（秒）：与空闲平静脸一致。 */
private const val SLOW_ORBIT_SEC: Float = 30f

/** 睡眠：眼睛半闭、视线下垂、头微低，卫星每分钟一圈，稳定浮出 z。 */
private val SLEEP_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.CLOSED,
        right = BallEyeForm.CLOSED,
        gaze = BallGaze.Point(0f, 22f),
        tilt = 6f,
        satellite = BallSatelliteMotion.Orbit(60f),
        breathSec = 6f,
        floatingZ = true,
    )

/** 充电：卫星绕行变快（约 1.4 秒一圈），眼睛眯成笑眼。 */
private val CHARGING_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.SQUINT,
        right = BallEyeForm.SQUINT,
        gaze = BallGaze.Point(0f, 4f),
        satellite = BallSatelliteMotion.Orbit(1.4f),
        breathSec = 2f,
    )

/** 低电量：眼睛下垂，卫星慢（50 秒一圈）。 */
private val TIRED_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.HALF,
        right = BallEyeForm.HALF,
        gaze = BallGaze.Point(0f, 30f),
        leftOffsetY = 18f,
        rightOffsetY = 18f,
        satellite = BallSatelliteMotion.Orbit(50f),
        breathSec = 4f,
    )

private val TAP_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.SQUINT,
        right = BallEyeForm.SQUINT,
        gaze = BallGaze.Point(0f, 4f),
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 2f,
    )

private val LONG_PRESS_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.WIDE,
        right = BallEyeForm.SQUINT,
        gaze = BallGaze.Point(-40f, -6f),
        tilt = -9f,
        leftOffsetY = -24f,
        rightOffsetY = -2f,
        gap = -2f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 2f,
    )

/** 拖动：眼睛半睁，视线由拖动方向决定（见 [BallFaceSpec.gazeDirection]）。 */
private val DRAG_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.HALF,
        right = BallEyeForm.HALF,
        gaze = BallGaze.Point(0f, 4f),
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 1.9f,
    )

private val FLING_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.WIDE,
        right = BallEyeForm.WIDE,
        gaze = BallGaze.Point(0f, 4f),
        shake = 6f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 1.5f,
    )

private val REVEAL_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.SQUINT,
        right = BallEyeForm.OPEN,
        gaze = BallGaze.Point(0f, 4f),
        tilt = -4f,
        shake = 3f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 2f,
    )

/** 唤醒：与平静脸相同，睡眠结束后平稳睁眼，没有额外动作。 */
private val WAKE_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.OPEN,
        right = BallEyeForm.OPEN,
        gaze = BallGaze.Point(0f, 4f),
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 4f,
    )

private val YAWN_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.CLOSED,
        right = BallEyeForm.CLOSED,
        gaze = BallGaze.Point(0f, -10f),
        tilt = -8f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 3f,
    )

private val GREET_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.SQUINT,
        right = BallEyeForm.OPEN,
        gaze = BallGaze.Point(0f, 4f),
        tilt = -6f,
        shake = 4f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 1.7f,
    )
