/**
 * 空闲态的表情定义：数据驱动的常态池（睡眠、充电、疲倦、哈欠、招手、点按、拖动、唤出）与手势反应。
 *
 * - 常态池：每张脸有权重，权重随 [BallPoolContext] 变化（时段、电量、网络、最近触摸）；权重为 0 的不在池中。
 * - 手势反应：点按、长按、甩出等事件的瞬时覆盖，优先于常态池；结束后回到常态池。
 * - 只在 [BallFaceMood.IDLE] 下生效，状态表情（等待、拦截、运行等）始终优先。
 *
 * 空闲时卫星一律慢绕（周期不短于 30 秒，睡眠 60 秒、疲倦 50 秒），不做快速旋转。
 */
package com.example.autop.core.ui.component.ballface

/** 空闲卫星绕行的最慢周期下限（秒）：池内脸的卫星不快于它。 */
private const val IDLE_ORBIT_MIN_SEC: Float = 30f

/** 基础脸的权重：不依赖数据，始终在池中。 */
private const val BASE_WEIGHT: Int = 10

/** 把卫星周期压到不快于 [IDLE_ORBIT_MIN_SEC] 之慢；已经更慢的（睡眠、疲倦）保持原样。 */
private fun BallFaceExpression.slowed(): BallFaceExpression {
    val orbit = satellite as? BallSatelliteMotion.Orbit ?: return this
    if (orbit.periodSec >= IDLE_ORBIT_MIN_SEC) return this
    return copy(satellite = BallSatelliteMotion.Orbit(IDLE_ORBIT_MIN_SEC))
}

private fun base(
    expression: BallFaceExpression,
    weightOf: (BallPoolContext) -> Int = { BASE_WEIGHT },
): IdleEntry = IdleEntry(expression.slowed(), weightOf)

/**
 * 常态池全部候选与权重。基础 13 张始终在池中；睡眠、充电、疲倦、哈欠、招手、点按、拖动、唤出
 * 只在对应数据出现时有权重。权重数值是设计预览里的建议值，可以在这里调。
 */
internal val IDLE_ENTRIES: List<IdleEntry> by lazy {
    listOf(
        base(BallFaceExpressions.calm),
        base(BallFaceExpressions.peek),
        base(BallFaceExpressions.focus),
        base(BallFaceExpressions.startWide),
        base(BallFaceExpressions.startTilt),
        base(BallFaceExpressions.startOpen),
        base(BallFaceExpressions.thinkCurious),
        base(BallFaceExpressions.appLook) { if (it.offline) 30 else BASE_WEIGHT },
        base(BallFaceExpressions.appTilt),
        base(BallFaceExpressions.appFar),
        base(BallFaceExpressions.gestureWatch),
        base(BallFaceExpressions.textSide),
        base(BallFaceExpressions.visionScan) { if (it.offline) 30 else BASE_WEIGHT },
        IdleEntry(SLEEP_EXPRESSION) { if (it.night) 150 else 0 },
        IdleEntry(TIRED_EXPRESSION) { if (it.lowBattery) 40 else 0 },
        IdleEntry(YAWN_EXPRESSION) { if (it.morning) 30 else 0 },
        IdleEntry(GREET_EXPRESSION) { (if (it.morning) 15 else 0) + (if (it.recentTouch) 5 else 0) },
        // 充电的笑眼与点按的眯眼外观相同，合并为一张：两种数据的权重相加。
        IdleEntry(TAP_EXPRESSION) { (if (it.recentTouch) 20 else 0) + (if (it.charging) 40 else 0) },
        IdleEntry(DRAG_EXPRESSION) { if (it.recentTouch) 8 else 0 },
        IdleEntry(REVEAL_EXPRESSION) { if (it.recentTouch) 6 else 0 },
    )
}

/** 手势反应的表情（空闲时覆盖常态池）。 */
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
 * 空闲时的手势反应覆盖：只在 [BallFaceMood.IDLE] 且有反应时返回表情，否则返回 null（走常态池）。
 */
public fun reactionOverrideOf(
    mood: BallFaceMood,
    reaction: BallReaction?,
): BallFaceExpression? = if (mood == BallFaceMood.IDLE) reaction?.let { reactionExpressionOf(it) } else null

/** 卫星慢绕的周期（秒）：与空闲平静脸一致。 */
private const val SLOW_ORBIT_SEC: Float = 30f

/** 睡眠：眼睛半闭、视线下垂、头微低，卫星每分钟一圈，稳定浮出 z。 */
internal val SLEEP_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.CLOSED,
        right = BallEyeForm.CLOSED,
        gaze = BallGaze.Point(0f, 22f),
        tilt = 6f,
        satellite = BallSatelliteMotion.Orbit(60f),
        breathSec = 6f,
        floatingZ = true,
    )

/** 疲倦（低电量）：眼睛下垂，卫星慢（50 秒一圈）。 */
internal val TIRED_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.HALF,
        right = BallEyeForm.HALF,
        gaze = BallGaze.Point(0f, 30f),
        leftOffsetY = 18f,
        rightOffsetY = 18f,
        satellite = BallSatelliteMotion.Orbit(50f),
        breathSec = 4f,
    )

internal val TAP_EXPRESSION: BallFaceExpression =
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
internal val DRAG_EXPRESSION: BallFaceExpression =
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

internal val REVEAL_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.SQUINT,
        right = BallEyeForm.OPEN,
        gaze = BallGaze.Point(0f, 4f),
        tilt = -4f,
        shake = 3f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 2f,
    )

/** 唤醒：与平静脸相同，睡眠中被触摸后平稳睁眼，没有额外动作。 */
private val WAKE_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.OPEN,
        right = BallEyeForm.OPEN,
        gaze = BallGaze.Point(0f, 4f),
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 4f,
    )

internal val YAWN_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.CLOSED,
        right = BallEyeForm.CLOSED,
        gaze = BallGaze.Point(0f, -10f),
        tilt = -8f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 3f,
    )

internal val GREET_EXPRESSION: BallFaceExpression =
    BallFaceExpression(
        left = BallEyeForm.SQUINT,
        right = BallEyeForm.OPEN,
        gaze = BallGaze.Point(0f, 4f),
        tilt = -6f,
        shake = 4f,
        satellite = BallSatelliteMotion.Orbit(SLOW_ORBIT_SEC),
        breathSec = 1.7f,
    )
