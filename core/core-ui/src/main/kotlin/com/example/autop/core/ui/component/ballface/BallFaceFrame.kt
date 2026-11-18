/**
 * 悬浮球表情的逐帧状态（纯 Kotlin，不依赖 Compose，便于单测）。
 *
 * 切换表情时不再整张脸淡入淡出（那会产生双影），而是：
 * - 数值（视线、偏移、旋转、倾斜、卫星位置与放大、呼吸）从切换瞬间的当前值逐帧插值到目标值；
 * - 眼睛的胶囊形态插值宽、高、圆角；笑眼、痛眼、叉眼这类笔画形态之间交叉淡入；
 * - 卫星内容（符号或会话数字）交叉淡入；
 * - 圆盘或开口环不参与过渡，始终只画一次。
 *
 * 过渡中途又来了新目标时，以当前显示的帧为起点（[FaceAnimator]），不会跳变。
 */
package com.example.autop.core.ui.component.ballface

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 一只眼的逐帧状态。[from] 非空时表示正在从旧形态过渡到 [form]：[mix] 为 0 时是旧形态，为 1 时是新形态。
 */
internal data class EyeFrame(
    val form: BallEyeForm,
    val from: BallEyeForm?,
    val mix: Float,
    val rotation: Float,
    val offsetY: Float,
)

/** 卫星内容：运行中的会话数字，或设计稿的符号。 */
internal sealed interface SatelliteContent {
    data class Glyph(
        val symbol: BallFaceSymbol,
    ) : SatelliteContent

    data class Digits(
        val text: String,
    ) : SatelliteContent
}

/** 卫星的逐帧状态。[contentFrom] 非空时表示卫星内容正在交叉淡入（[contentMix] 同 [EyeFrame.mix]）。 */
internal data class SatelliteFrame(
    val content: SatelliteContent,
    val contentFrom: SatelliteContent?,
    val contentMix: Float,
    val degrees: Float,
    val x: Float,
    val y: Float,
    val scale: Float,
)

/** 一帧的全部派生参数。 */
internal data class BallFaceFrame(
    val left: EyeFrame,
    val right: EyeFrame,
    val gazeX: Float,
    val gazeY: Float,
    val gap: Float,
    val breath: Float,
    val tilt: Float,
    val shake: Float,
    val satellite: SatelliteFrame,
    /** 浮动 z 的时刻（秒）；null 表示不画 z。多个 z 的位置由 [floatingZsOf] 按此时刻与 [BallZStyle] 推出。 */
    val zTime: Float? = null,
)

/** 切换表情的过渡时长（秒）：与 [BallFaceDirector.CROSSFADE_MS] 一致。 */
private val TRANSITION_SEC: Float = BallFaceDirector.CROSSFADE_MS / MILLIS_PER_SECOND

private const val MILLIS_PER_SECOND: Float = 1_000f

/**
 * 时刻 [t]（秒）的一帧参数。公式逐条对应设计稿 Face.tick / satAngleOf / gazeOf：
 * 卫星角、视线、眨眼、呼吸、倾斜与抖动。[digits] 非空时卫星内容为会话数字，否则为设计稿符号。
 */
internal fun ballFaceFrameOf(
    e: BallFaceExpression,
    t: Float,
    motion: Boolean,
    digits: String? = null,
    blinking: Boolean = false,
    satellitePaused: Boolean = false,
): BallFaceFrame {
    // 卫星停靠（网络断开）时只让卫星的时间停下，脸的其它动作照常。
    val satT = if (satellitePaused) pausedTimeOf(t) else t
    val satDeg = satelliteDegreesOf(e.satellite, satT)
    val gaze = gazeOf(e.gaze, satDeg, t)
    val blink = motion && blinking
    val drifting = e.satellite == BallSatelliteMotion.Drift
    return BallFaceFrame(
        left = eyeOf(if (blink) blinked(e.left) else e.left, e.leftRotation, e.leftOffsetY),
        right = eyeOf(if (blink) blinked(e.right) else e.right, e.rightRotation, e.rightOffsetY),
        gazeX = gaze.first,
        gazeY = gaze.second,
        gap = e.gap,
        breath = if (motion) 1f + BREATH_AMPLITUDE * sin(t / e.breathSec * TWO_PI) else 1f,
        tilt = e.tilt + if (drifting) sin(t * TILT_WOBBLE_RATE) * TILT_WOBBLE else 0f,
        shake = if (motion && e.shake != 0f) sin(t * SHAKE_RATE) * e.shake else 0f,
        satellite = satelliteFrameOf(e, satT, satDeg, motion, digits),
        zTime = if (motion && e.floatingZ) t else null,
    )
}

/** 网络断开时卫星停住的周期与停住时长（秒）：每个周期末尾停 [OFFLINE_PAUSE_SEC]。 */
private const val OFFLINE_CYCLE_SEC: Float = 4f
private const val OFFLINE_PAUSE_SEC: Float = 1.2f

/** 卫星停住时的“卫星时间”：只累计未停住的时间，停住期间保持不变，因此卫星不会跳位。 */
internal fun pausedTimeOf(t: Float): Float {
    val cycles = floor(t / OFFLINE_CYCLE_SEC)
    val within = t - cycles * OFFLINE_CYCLE_SEC
    val pausedNow = (within - (OFFLINE_CYCLE_SEC - OFFLINE_PAUSE_SEC)).coerceIn(0f, OFFLINE_PAUSE_SEC)
    return t - (cycles * OFFLINE_PAUSE_SEC + pausedNow)
}

private fun satelliteFrameOf(
    e: BallFaceExpression,
    t: Float,
    satDeg: Float,
    motion: Boolean,
    digits: String?,
): SatelliteFrame {
    val (driftX, driftY) = driftOf(e.satellite, t)
    val rad = Math.toRadians((satDeg - ANGLE_OFFSET).toDouble())
    val pulse = if (motion) pulseScaleOf(e.satellitePulseAmp, t) else 1f
    return SatelliteFrame(
        content = digits?.let { SatelliteContent.Digits(it) } ?: SatelliteContent.Glyph(e.symbol),
        contentFrom = null,
        contentMix = 1f,
        degrees = satDeg,
        x = driftX + cos(rad).toFloat() * e.satelliteOrbit,
        y = driftY + sin(rad).toFloat() * e.satelliteOrbit,
        scale = e.satelliteScale * pulse,
    )
}

/**
 * 从 [from]（当前显示的帧）过渡到 [to]，[k] 为已缓动的进度（0 到 1）。
 * 数值线性插值；形态与内容在 [k] 为 1 之前保留来源，用于交叉淡入或胶囊插值。
 */
internal fun blendFrames(
    from: BallFaceFrame,
    to: BallFaceFrame,
    k: Float,
): BallFaceFrame =
    BallFaceFrame(
        left = blendEye(from.left, to.left, k),
        right = blendEye(from.right, to.right, k),
        gazeX = lerp(from.gazeX, to.gazeX, k),
        gazeY = lerp(from.gazeY, to.gazeY, k),
        gap = lerp(from.gap, to.gap, k),
        breath = lerp(from.breath, to.breath, k),
        tilt = lerp(from.tilt, to.tilt, k),
        shake = lerp(from.shake, to.shake, k),
        satellite = blendSatellite(from.satellite, to.satellite, k),
        zTime = to.zTime,
    )

/** 三次缓入缓出：起止速度为零，过渡两端不突兀。 */
internal fun easeInOut(k: Float): Float {
    val x = k.coerceIn(0f, 1f)
    if (x < HALF) return EASE_IN_SCALE * x * x * x
    val u = 2f - 2f * x
    return 1f - u * u * u / 2f
}

/**
 * 逐帧的表情播放器：切换目标时，以当前显示的帧为起点过渡到新目标；动效关闭时直接落到目标。
 * 只在绘制阶段调用 [frameAt]，[setTarget] 可在组合阶段调用，二者都不触发重组。
 */
internal class FaceAnimator {
    private var target: Target? = null
    private var pending = false
    private var transitionFrom: BallFaceFrame? = null
    private var startSec = 0f
    private var shown: BallFaceFrame? = null

    /** 设置目标；[animate] 为假或首次设置时不过渡。 */
    fun setTarget(
        expression: BallFaceExpression,
        digits: String?,
        animate: Boolean,
    ) {
        val next = Target(expression, digits)
        if (next == target) return
        pending = animate && target != null
        target = next
    }

    /** 时刻 [t] 的显示帧。 */
    fun frameAt(
        t: Float,
        motion: Boolean,
        blinking: Boolean = false,
        satellitePaused: Boolean = false,
    ): BallFaceFrame {
        val current = checkNotNull(target) { "setTarget 必须先于 frameAt 调用" }
        val to = ballFaceFrameOf(current.expression, t, motion, current.digits, blinking, satellitePaused)
        if (pending) {
            pending = false
            transitionFrom = shown
            startSec = t
        }
        val from = transitionFrom
        val frame =
            if (from == null || !motion) {
                transitionFrom = null
                to
            } else {
                val k = (t - startSec) / TRANSITION_SEC
                if (k >= 1f) {
                    transitionFrom = null
                    to
                } else {
                    blendFrames(from, to, easeInOut(k.coerceAtLeast(0f)))
                }
            }
        shown = frame
        return frame
    }

    private data class Target(
        val expression: BallFaceExpression,
        val digits: String?,
    )
}

private const val HALF: Float = 0.5f

private fun eyeOf(
    form: BallEyeForm,
    rotation: Float,
    offsetY: Float,
): EyeFrame = EyeFrame(form = form, from = null, mix = 1f, rotation = rotation, offsetY = offsetY)

private fun blendEye(
    from: EyeFrame,
    to: EyeFrame,
    k: Float,
): EyeFrame =
    EyeFrame(
        form = to.form,
        from = dominantEye(from).takeIf { it != to.form },
        mix = k,
        rotation = lerp(from.rotation, to.rotation, k),
        offsetY = lerp(from.offsetY, to.offsetY, k),
    )

/** 当前显示上占主导的形态：过渡进度不足一半时仍以来源为主。 */
private fun dominantEye(eye: EyeFrame): BallEyeForm = if (eye.from != null && eye.mix < HALF) eye.from else eye.form

private fun blendSatellite(
    from: SatelliteFrame,
    to: SatelliteFrame,
    k: Float,
): SatelliteFrame =
    SatelliteFrame(
        content = to.content,
        contentFrom = dominantContent(from).takeIf { it != to.content },
        contentMix = k,
        degrees = lerp(from.degrees, to.degrees, k),
        x = lerp(from.x, to.x, k),
        y = lerp(from.y, to.y, k),
        scale = lerp(from.scale, to.scale, k),
    )

private fun dominantContent(sat: SatelliteFrame): SatelliteContent =
    if (sat.contentFrom != null && sat.contentMix < HALF) sat.contentFrom else sat.content

private fun lerp(
    a: Float,
    b: Float,
    k: Float,
): Float = a + (b - a) * k

private fun blinked(form: BallEyeForm): BallEyeForm =
    when (form) {
        BallEyeForm.OPEN, BallEyeForm.HALF, BallEyeForm.WIDE -> BallEyeForm.CLOSED
        else -> form
    }

private fun satelliteDegreesOf(
    motion: BallSatelliteMotion,
    t: Float,
): Float =
    when (motion) {
        is BallSatelliteMotion.Orbit -> REST_DEGREES + t / motion.periodSec * FULL_TURN
        BallSatelliteMotion.Rest -> REST_DEGREES
        BallSatelliteMotion.Drift -> REST_DEGREES + sin(t * TWO_PI / DRIFT_PERIOD_SEC) * DRIFT_DEGREES
    }

/** 停靠漂移时卫星相对轨道位置的水平、垂直偏移；其它运动为零。 */
private fun driftOf(
    sat: BallSatelliteMotion,
    t: Float,
): Pair<Float, Float> =
    if (sat == BallSatelliteMotion.Drift) {
        sin(t * DRIFT_X_RATE) * DRIFT_X to sin(t * DRIFT_Y_RATE + DRIFT_Y_PHASE) * DRIFT_Y
    } else {
        0f to 0f
    }

private fun gazeOf(
    gaze: BallGaze,
    satDeg: Float,
    t: Float,
): Pair<Float, Float> =
    when (gaze) {
        is BallGaze.Point -> gaze.x to gaze.y
        BallGaze.FollowSatellite -> {
            val r = Math.toRadians((satDeg - ANGLE_OFFSET).toDouble())
            (cos(r) * GAZE_RADIUS_X).toFloat() to (sin(r) * GAZE_RADIUS_Y + GAZE_Y_OFFSET).toFloat()
        }
        is BallGaze.Sweep -> sin(t * SWEEP_RATE) * gaze.amplitude to gaze.y
    }

private fun pulseScaleOf(
    amp: Float?,
    t: Float,
): Float = if (amp == null) 1f else 1f + amp * pulseOf(t, PULSE_PERIOD_SEC)

private fun pulseOf(
    t: Float,
    period: Float,
): Float = HALF - HALF * cos(t / period * TWO_PI)

private const val REST_DEGREES: Float = 45f
private const val ANGLE_OFFSET: Float = 90f
private const val FULL_TURN: Float = 360f
private const val TWO_PI: Float = 6.2831855f

/** 一次眨眼的闭眼时长（秒）。眨眼起点由 [AutopBallFace] 的时钟按 2–6 秒随机给出。 */
internal const val BLINK_SHUT_SEC: Float = 0.15f

private const val BREATH_AMPLITUDE: Float = 0.009f
private const val DRIFT_PERIOD_SEC: Float = 4f
private const val DRIFT_DEGREES: Float = 8f
private const val DRIFT_X: Float = 34f
private const val DRIFT_Y: Float = 16f
private const val DRIFT_X_RATE: Float = 1.4f
private const val DRIFT_Y_RATE: Float = 0.9f
private const val DRIFT_Y_PHASE: Float = 1f
private const val TILT_WOBBLE: Float = 3f
private const val TILT_WOBBLE_RATE: Float = 1.1f
private const val PULSE_PERIOD_SEC: Float = 2.4f
private const val GAZE_RADIUS_X: Float = 38f
private const val GAZE_RADIUS_Y: Float = 32f
private const val GAZE_Y_OFFSET: Float = 6f
private const val SWEEP_RATE: Float = 1.3f
private const val SHAKE_RATE: Float = 20f
private const val EASE_IN_SCALE: Float = 4f
