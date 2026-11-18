/**
 * 表情参数（设计稿 `.tmp-icons/ball-face-events.html` 的 EXPRS 逐项移植）：眼型、视线、偏移、
 * 倾斜、卫星运动与符号成套描述一张脸。固定态由 [fixedBallFaceExpressionOf] 唯一映射；
 * 表演态的候选集合见 [BallFaceDirector]。
 */
@file:Suppress("MagicNumber")

package com.example.autop.core.ui.component.ballface

import androidx.compose.runtime.Immutable

/** 单只眼睛的画法（设计稿 FORM 四种胶囊 + 笑眼/痛眼/叉眼/怒眼）。 */
public enum class BallEyeForm {
    /** 大睁胶囊。 */
    OPEN,

    /** 半睑（上下扁）。 */
    HALF,

    /** 细眯。 */
    SQUINT,

    /** 闭合成线。 */
    CLOSED,

    /** 特大睁眼。 */
    WIDE,

    /** 压眉（怒）。 */
    ANGRY,

    /** 上弧笑眼。 */
    JOY,

    /** > < 挤眼（痛）。 */
    PAIN,

    /** 叉眼（断开）。 */
    X,
}

/** 视线：固定点、跟随卫星角、或左右扫视。 */
public sealed interface BallGaze {
    /** 固定偏移（设计稿单位）。 */
    public data class Point(
        val x: Float,
        val y: Float,
    ) : BallGaze

    /** 视线跟随卫星所在方向（设计稿 gaze:'sat'）。 */
    public data object FollowSatellite : BallGaze

    /** 左右扫视：x = sin(t × 1.3) × [amplitude]，y 固定。 */
    public data class Sweep(
        val amplitude: Float,
        val y: Float,
    ) : BallGaze
}

/** 卫星运动。 */
public sealed interface BallSatelliteMotion {
    /** 绕行：[periodSec] 秒一圈（工作中）。 */
    public data class Orbit(
        val periodSec: Float,
    ) : BallSatelliteMotion

    /** 停靠右上 45°（固定态）。 */
    public data object Rest : BallSatelliteMotion

    /** 停靠附近漂移（无障碍断开）。 */
    public data object Drift : BallSatelliteMotion
}

/**
 * 一张脸的完整描述。除 [satellite]、[symbol] 外均为设计稿的数值。
 *
 * @property satelliteOrbit 卫星轨道半径：默认 537（静止/空闲），表演态 470。
 * @property satelliteScale 卫星放大倍数：固定态 1.9，表演态 1.75。
 * @property satellitePulseAmp 卫星脉冲幅度；null 表示不脉冲。
 */
@Immutable
public data class BallFaceExpression(
    val left: BallEyeForm,
    val right: BallEyeForm,
    val gaze: BallGaze,
    val gap: Float = 0f,
    val tilt: Float = 0f,
    val leftRotation: Float = 0f,
    val rightRotation: Float = 0f,
    val leftOffsetY: Float = 0f,
    val rightOffsetY: Float = 0f,
    val satellite: BallSatelliteMotion = BallSatelliteMotion.Orbit(FAST_ORBIT_PERIOD_SEC),
    val satelliteOrbit: Float = ORBIT_IDLE,
    val satelliteScale: Float = 1f,
    val satellitePulseAmp: Float? = null,
    val symbol: BallFaceSymbol = BallFaceSymbol.NONE,
    val breathSec: Float = BREATH_DEFAULT_SEC,
    val shake: Float = 0f,
    /** 稳定浮出 z（睡眠）：每个周期从头顶右侧升起并淡出。 */
    val floatingZ: Boolean = false,
)

/** 轨道半径：空闲/固定态。 */
internal const val ORBIT_IDLE: Float = 537f

/** 轨道半径：表演态（绕行）。 */
internal const val ORBIT_PERFORM: Float = 470f

/** 绕行周期（秒）。 */
internal const val FAST_ORBIT_PERIOD_SEC: Float = 2.6f

/** 呼吸默认周期（秒）。 */
internal const val BREATH_DEFAULT_SEC: Float = 3.2f

/** 表演态卫星放大倍数。 */
internal const val SCALE_PERFORM: Float = 1.75f

/** 固定态卫星放大倍数。 */
internal const val SCALE_FIXED: Float = 1.9f

/** 固定态卫星脉冲默认幅度。 */
internal const val PULSE_DEFAULT: Float = 0.06f

/** 录制态卫星脉冲幅度。 */
internal const val PULSE_RECORDING: Float = 0.05f

/** 设计稿 EXPRS 表。字段名与设计稿 key 对应，便于逐条对照。 */
public object BallFaceExpressions {
    /** 空闲：平静胶囊，30s 慢绕。 */
    public val calm: BallFaceExpression =
        BallFaceExpression(
            left = BallEyeForm.OPEN,
            right = BallEyeForm.OPEN,
            gaze = BallGaze.Point(0f, 4f),
            satellite = BallSatelliteMotion.Orbit(30f),
            breathSec = 4f,
        )

    /** 起跑：睁大张望，卫星推进箭头。 */
    public val startWide: BallFaceExpression =
        performance(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.WIDE,
            gaze = BallGaze.Point(52f, -30f),
            offsetY = -22f,
            gap = 26f,
            breathSec = 1.5f,
            symbol = BallFaceSymbol.CHEVRON,
        )

    /** 起跑：微微仰头，卫星火苗。 */
    public val startTilt: BallFaceExpression =
        performance(
            left = BallEyeForm.OPEN,
            right = BallEyeForm.OPEN,
            gaze = BallGaze.Point(-8f, -34f),
            tilt = -6f,
            offsetY = -16f,
            gap = 6f,
            breathSec = 1.7f,
            symbol = BallFaceSymbol.FLAME,
        )

    /** 起跑：提气，卫星火箭。 */
    public val startOpen: BallFaceExpression =
        performance(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.WIDE,
            gaze = BallGaze.Point(0f, -26f),
            offsetY = -34f,
            gap = 34f,
            breathSec = 1.4f,
            symbol = BallFaceSymbol.ROCKET,
        )

    /** 思考：专注，视线跟卫星，卫星省略号。 */
    public val focus: BallFaceExpression =
        performance(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.FollowSatellite,
            offsetY = 4f,
            gap = -12f,
            breathSec = 1.6f,
            symbol = BallFaceSymbol.ELLIPSIS,
        )

    /** 思考：向上想，卫星灯泡。 */
    public val thinkUp: BallFaceExpression =
        performance(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Point(-6f, -44f),
            tilt = -4f,
            offsetY = -52f,
            gap = -4f,
            breathSec = 1.6f,
            symbol = BallFaceSymbol.BULB,
        )

    /** 思考：闭眼沉思，卫星月亮。 */
    public val thinkClosed: BallFaceExpression =
        performance(
            left = BallEyeForm.CLOSED,
            right = BallEyeForm.CLOSED,
            gaze = BallGaze.Point(0f, 0f),
            offsetY = -30f,
            breathSec = 4.2f,
            symbol = BallFaceSymbol.MOON,
        )

    /** 思考：皱眉想，卫星沙漏。 */
    public val thinkSquint: BallFaceExpression =
        performance(
            left = BallEyeForm.SQUINT,
            right = BallEyeForm.SQUINT,
            gaze = BallGaze.Point(-48f, -10f),
            tilt = -6f,
            offsetY = -38f,
            gap = -24f,
            breathSec = 1.8f,
            symbol = BallFaceSymbol.HOURGLASS,
        )

    /** 思考：疑惑，一大一小，卫星星光。 */
    public val thinkCurious: BallFaceExpression =
        performance(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.SQUINT,
            gaze = BallGaze.Point(-40f, -6f),
            tilt = -9f,
            leftOffsetY = -24f,
            rightOffsetY = -2f,
            gap = -2f,
            breathSec = 2f,
            symbol = BallFaceSymbol.SPARK,
        )

    /** 思考：偷瞄，卫星眼睛。 */
    public val peek: BallFaceExpression =
        performance(
            left = BallEyeForm.CLOSED,
            right = BallEyeForm.OPEN,
            gaze = BallGaze.Point(60f, 2f),
            tilt = 6f,
            leftOffsetY = 10f,
            rightOffsetY = -14f,
            breathSec = 1.9f,
            symbol = BallFaceSymbol.EYE,
        )

    /** 手势：眯眼盯手，卫星指针。 */
    public val gestureWatch: BallFaceExpression =
        performance(
            left = BallEyeForm.SQUINT,
            right = BallEyeForm.SQUINT,
            gaze = BallGaze.Point(-6f, 52f),
            tilt = 4f,
            offsetY = 26f,
            gap = -18f,
            breathSec = 1.7f,
            symbol = BallFaceSymbol.CURSOR,
        )

    /** 手势：跟手，卫星拖拽箭头。 */
    public val gestureTrace: BallFaceExpression =
        performance(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Sweep(amplitude = 50f, y = 40f),
            tilt = 3f,
            offsetY = 18f,
            gap = -10f,
            breathSec = 1.9f,
            symbol = BallFaceSymbol.DRAG,
        )

    /** 手势：点头确认，卫星对勾。 */
    public val gestureConfirm: BallFaceExpression =
        performance(
            left = BallEyeForm.CLOSED,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Point(28f, 30f),
            tilt = 5f,
            leftOffsetY = -14f,
            rightOffsetY = 14f,
            breathSec = 2f,
            symbol = BallFaceSymbol.CHECK,
        )

    /** 文本：垂眼，卫星文字行。 */
    public val textDown: BallFaceExpression =
        performance(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Point(0f, 56f),
            offsetY = 22f,
            gap = 4f,
            breathSec = 2.2f,
            symbol = BallFaceSymbol.LINES,
        )

    /** 文本：侧目，卫星铅笔。 */
    public val textSide: BallFaceExpression =
        performance(
            left = BallEyeForm.CLOSED,
            right = BallEyeForm.OPEN,
            gaze = BallGaze.Point(56f, 18f),
            tilt = 3f,
            offsetY = 6f,
            breathSec = 2.2f,
            symbol = BallFaceSymbol.PENCIL,
        )

    /** 文本：专注输入，卫星文本光标。 */
    public val textType: BallFaceExpression =
        performance(
            left = BallEyeForm.SQUINT,
            right = BallEyeForm.SQUINT,
            gaze = BallGaze.Point(0f, 46f),
            offsetY = 18f,
            gap = -12f,
            breathSec = 2f,
            symbol = BallFaceSymbol.IBEAM,
        )

    /** 视觉：扫视，卫星放大镜。 */
    public val visionScan: BallFaceExpression =
        performance(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.WIDE,
            gaze = BallGaze.Sweep(amplitude = 80f, y = -10f),
            offsetY = -14f,
            gap = -20f,
            breathSec = 1.6f,
            symbol = BallFaceSymbol.LENS,
        )

    /** 视觉：定睛，卫星眼睛。 */
    public val visionStare: BallFaceExpression =
        performance(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.WIDE,
            gaze = BallGaze.Point(0f, -16f),
            offsetY = -14f,
            gap = 18f,
            breathSec = 1.6f,
            symbol = BallFaceSymbol.EYE,
        )

    /** 视觉：细看，卫星扫描框。 */
    public val visionSquint: BallFaceExpression =
        performance(
            left = BallEyeForm.SQUINT,
            right = BallEyeForm.SQUINT,
            gaze = BallGaze.Point(0f, -10f),
            offsetY = -8f,
            gap = 20f,
            breathSec = 1.9f,
            symbol = BallFaceSymbol.SCAN,
        )

    /** 启动应用：抬头找，卫星应用格。 */
    public val appLook: BallFaceExpression =
        performance(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.WIDE,
            gaze = BallGaze.Point(0f, -50f),
            tilt = -2f,
            offsetY = -56f,
            breathSec = 1.7f,
            symbol = BallFaceSymbol.GRID,
        )

    /** 启动应用：歪头看，卫星窗口。 */
    public val appTilt: BallFaceExpression =
        performance(
            left = BallEyeForm.OPEN,
            right = BallEyeForm.OPEN,
            gaze = BallGaze.Point(26f, -24f),
            tilt = 12f,
            leftOffsetY = -30f,
            rightOffsetY = -18f,
            breathSec = 2f,
            symbol = BallFaceSymbol.WINDOW,
        )

    /** 启动应用：远眺，卫星火箭。 */
    public val appFar: BallFaceExpression =
        performance(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Point(66f, -34f),
            tilt = 4f,
            offsetY = -28f,
            gap = 8f,
            breathSec = 2.1f,
            symbol = BallFaceSymbol.ROCKET,
        )

    /** 整理：消化，卫星漏斗。 */
    public val compact: BallFaceExpression =
        performance(
            left = BallEyeForm.CLOSED,
            right = BallEyeForm.CLOSED,
            gaze = BallGaze.Point(0f, 0f),
            offsetY = -18f,
            breathSec = 4.6f,
            symbol = BallFaceSymbol.FUNNEL,
        )

    /** 整理：舒气，卫星风。 */
    public val compactBreath: BallFaceExpression =
        performance(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Point(0f, 16f),
            offsetY = 2f,
            gap = -6f,
            breathSec = 5.2f,
            symbol = BallFaceSymbol.WIND,
        )

    /** 等待回答：问号，睁大看向右上。固定态。 */
    public val waiting: BallFaceExpression =
        fixed(
            left = BallEyeForm.WIDE,
            right = BallEyeForm.WIDE,
            gaze = BallGaze.Point(46f, -20f),
            tilt = 4f,
            offsetY = -16f,
            breathSec = 2.4f,
            symbol = BallFaceSymbol.QUESTION,
        )

    /** 已暂停：双竖条，垂眼。固定态。 */
    public val paused: BallFaceExpression =
        fixed(
            left = BallEyeForm.HALF,
            right = BallEyeForm.HALF,
            gaze = BallGaze.Point(0f, 40f),
            offsetY = 24f,
            breathSec = 4.5f,
            symbol = BallFaceSymbol.PAUSE,
        )

    /** 被拦截：感叹号，压眉抗议。固定态。 */
    public val blocked: BallFaceExpression =
        fixed(
            left = BallEyeForm.ANGRY,
            right = BallEyeForm.ANGRY,
            gaze = BallGaze.Point(-12f, 10f),
            leftRotation = 16f,
            rightRotation = -16f,
            offsetY = 4f,
            gap = -14f,
            breathSec = 2.4f,
            symbol = BallFaceSymbol.EXCLAIM,
        )

    /** 无障碍断开：虚线空环，叉眼漂移。固定态。 */
    public val lost: BallFaceExpression =
        BallFaceExpression(
            left = BallEyeForm.X,
            right = BallEyeForm.X,
            gaze = BallGaze.Point(24f, -22f),
            satellite = BallSatelliteMotion.Drift,
            satelliteScale = SCALE_FIXED,
            symbol = BallFaceSymbol.DASHED_RING,
            breathSec = 4.5f,
        )

    /** 录制中：侧瞄，方形点。固定态。 */
    public val recording: BallFaceExpression =
        fixed(
            left = BallEyeForm.CLOSED,
            right = BallEyeForm.OPEN,
            gaze = BallGaze.Point(-44f, 8f),
            tilt = 3f,
            offsetY = 2f,
            breathSec = 2.8f,
            symbol = BallFaceSymbol.SQUARE,
            pulse = PULSE_RECORDING,
        )

    /** 完成：心形，上弧笑眼。固定态。 */
    public val success: BallFaceExpression =
        fixed(
            left = BallEyeForm.JOY,
            right = BallEyeForm.JOY,
            gaze = BallGaze.Point(0f, -2f),
            offsetY = -14f,
            breathSec = 2f,
            symbol = BallFaceSymbol.HEART,
        )

    /** 失败：💢 怒气纹，痛眼挤压，轻微抖动。固定态。 */
    public val failure: BallFaceExpression =
        fixed(
            left = BallEyeForm.PAIN,
            right = BallEyeForm.PAIN,
            gaze = BallGaze.Point(0f, 12f),
            offsetY = 6f,
            breathSec = 1.8f,
            symbol = BallFaceSymbol.ANGER,
            shake = 2.4f,
        )

    private fun performance(
        left: BallEyeForm,
        right: BallEyeForm,
        gaze: BallGaze,
        tilt: Float = 0f,
        offsetY: Float = 0f,
        leftOffsetY: Float = offsetY,
        rightOffsetY: Float = offsetY,
        gap: Float = 0f,
        breathSec: Float,
        symbol: BallFaceSymbol,
    ): BallFaceExpression =
        BallFaceExpression(
            left = left,
            right = right,
            gaze = gaze,
            gap = gap,
            tilt = tilt,
            leftOffsetY = leftOffsetY,
            rightOffsetY = rightOffsetY,
            satelliteOrbit = ORBIT_PERFORM,
            satelliteScale = SCALE_PERFORM,
            symbol = symbol,
            breathSec = breathSec,
        )

    @Suppress("LongParameterList") // 与 performance 同构的参数表，拆开会割裂设计稿的对照关系。
    private fun fixed(
        left: BallEyeForm,
        right: BallEyeForm,
        gaze: BallGaze,
        tilt: Float = 0f,
        offsetY: Float = 0f,
        gap: Float = 0f,
        leftRotation: Float = 0f,
        rightRotation: Float = 0f,
        breathSec: Float,
        symbol: BallFaceSymbol,
        pulse: Float = PULSE_DEFAULT,
        shake: Float = 0f,
    ): BallFaceExpression =
        BallFaceExpression(
            left = left,
            right = right,
            gaze = gaze,
            gap = gap,
            tilt = tilt,
            leftRotation = leftRotation,
            rightRotation = rightRotation,
            leftOffsetY = offsetY,
            rightOffsetY = offsetY,
            satellite = BallSatelliteMotion.Rest,
            satelliteScale = SCALE_FIXED,
            satellitePulseAmp = pulse,
            symbol = symbol,
            breathSec = breathSec,
            shake = shake,
        )
}

/**
 * 固定态 → 表情（唯一映射，不随机）。表演态（起跑/思考/工具/整理）返回 null，由
 * [BallFaceDirector] 按场景抽取。
 */
public fun fixedBallFaceExpressionOf(mood: BallFaceMood): BallFaceExpression? =
    when (mood) {
        BallFaceMood.IDLE -> null
        BallFaceMood.WAITING -> BallFaceExpressions.waiting
        BallFaceMood.PAUSED -> BallFaceExpressions.paused
        BallFaceMood.BLOCKED -> BallFaceExpressions.blocked
        BallFaceMood.LOST -> BallFaceExpressions.lost
        BallFaceMood.RECORDING -> BallFaceExpressions.recording
        BallFaceMood.SUCCESS -> BallFaceExpressions.success
        BallFaceMood.FAILURE -> BallFaceExpressions.failure
        BallFaceMood.RUNNING_START, BallFaceMood.RUNNING -> null
    }
