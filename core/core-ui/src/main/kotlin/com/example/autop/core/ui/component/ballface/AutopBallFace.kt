/**
 * 悬浮球表情渲染（设计稿 `.tmp-icons/ball-face-events.html`）。
 *
 * 绘制坐标系：设计稿 1408 单位，原点在球心，盘半径 512。Canvas 按最短边缩放，所以同一份几何
 * 在 52dp 球、控制条、卡片上保持比例。逐帧状态与过渡见 [BallFaceFrame.kt]（纯逻辑，可单测）。
 *
 * 动效受 [LocalMotionEnabled] 与 [BallFaceSpec.motionEnabled] 双重控制；关闭时固定在
 * [STATIC_TIME_SEC] 的静态落帧，切换直接落到目标。
 *
 * 会话数字（[BallFaceSpec.count]）：表演态时数字代替卫星符号；固定态时数字放在卫星角标上，
 * 问号、感叹号等提醒符号保持不变。
 */
@file:Suppress("MagicNumber")

package com.example.autop.core.ui.component.ballface

import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.example.autop.core.ui.theme.LocalBallFaceColor
import com.example.autop.core.ui.theme.LocalBallFaceMinHoldMs
import com.example.autop.core.ui.theme.LocalBallRerollMs
import com.example.autop.core.ui.theme.LocalBallShape
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.PetColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 表情渲染（参数形式）。 */
@Composable
public fun AutopBallFace(
    mood: BallFaceMood,
    modifier: Modifier = Modifier,
    variant: BallFaceVariant? = null,
    paint: BallFacePaint = BallFacePaint.THEME,
    tint: Color? = null,
    motionEnabled: Boolean = true,
) {
    AutopBallFace(
        spec =
            BallFaceSpec(
                mood = mood,
                variant = variant,
                paint = paint,
                tint = tint,
                motionEnabled = motionEnabled,
            ),
        modifier = modifier,
    )
}

/**
 * 表情渲染（Spec 形式）：固定态直接取表情，表演态经导演抽取。
 * 换脸先经过最短驻留（[LocalBallFaceMinHoldMs]，ADR-0045），等待回答、拦截、断开立即显示。
 */
@Composable
public fun AutopBallFace(
    spec: BallFaceSpec,
    modifier: Modifier = Modifier,
) {
    val scene = if (spec.mood == BallFaceMood.IDLE) BallFaceScene.IDLE else spec.scene
    val held = rememberHeldFace(spec.mood, scene, spec.seed, LocalBallFaceMinHoldMs.current)
    HeldBallFace(spec.copy(mood = held.mood, scene = held.scene, seed = held.seed), modifier)
}

/** 驻留后的表情键：切换目标在驻留时长满足后才生效（[FaceDwell]）。 */
@Composable
private fun rememberHeldFace(
    mood: BallFaceMood,
    scene: BallFaceScene,
    seed: Long,
    holdMs: Long,
): HeldFace {
    val target = heldFaceOf(mood, scene, seed)
    val dwell = remember { FaceDwell() }
    var shown by remember { mutableStateOf(target) }
    // 目标变化时重启：驻留期间又来了新目标，只按最新目标结算。
    LaunchedEffect(target, holdMs) {
        val wait = dwell.waitMs(target, holdMs)
        if (wait > 0L) delay(wait)
        dwell.onShown(target)
        shown = target
    }
    return shown
}

/** 绘制一张已确定的脸（驻留之后的结果）。 */
@Composable
private fun HeldBallFace(
    spec: BallFaceSpec,
    modifier: Modifier,
) {
    val motion = spec.motionEnabled && LocalMotionEnabled.current
    // 空闲态的手势反应与环境状态（睡眠、充电、低电量）优先于表演池；状态表情不受影响。
    val fixed = fixedBallFaceExpressionOf(spec.mood) ?: idleOverrideOf(spec.mood, spec.ambient, spec.reaction)
    val scene = if (spec.mood == BallFaceMood.RUNNING_START) BallFaceScene.STARTING else spec.scene
    // 思考段与混合池的轮换序号由同一条逐帧时钟推进（不另起协程）：只在序号变化时重组。
    val rerolls = fixed == null && (scene == BallFaceScene.SAMPLING || scene == BallFaceScene.MIXED)
    val rerollSeconds = if (rerolls) LocalBallRerollMs.current / MILLIS_PER_SECOND else 0f
    // 空闲态按事件种子给出 5–10 秒的随机间隔（见 BallFaceDirector.idleRerollSeconds）。
    val idle = fixed == null && scene == BallFaceScene.IDLE
    val clock =
        rememberFaceClock(enabled = motion, rerollSeconds = rerollSeconds, jitterSeed = if (idle) spec.seed else null)
    val performed = rememberBallFaceExpression(scene = scene, seed = spec.seed, reroll = clock.rerolls.value)
    val measurer = rememberTextMeasurer()
    val label =
        spec.count?.takeIf { it > 0 }?.let { count ->
            FaceLabel(
                text = if (count > MAX_DIGIT) "$MAX_DIGIT+" else count.toString(),
                inSatellite = fixed == null,
                measurer = measurer,
            )
        }
    BallFaceCanvas(
        expression = fixed ?: performed,
        style =
            FaceStyle(
                variant = spec.variant ?: LocalBallShape.current,
                palette =
                    facePaletteOf(spec.paint, spec.tint, MaterialTheme.colorScheme, LocalBallFaceColor.current),
                label = label,
                measurer = measurer,
            ),
        motion = motion,
        clock = clock,
        satellitePaused = spec.offline,
        gazeDirection =
            spec.gazeDirection.takeIf {
                spec.mood == BallFaceMood.IDLE && spec.reaction == BallReaction.DRAG
            },
        modifier = modifier,
    )
}

/**
 * 表演态抽取（导演的 Compose 层）：同一事件种子与轮换序号下结果稳定；序号由时钟给出，
 * 动效关闭时恒为 0（不轮换）。
 */
@Composable
internal fun rememberBallFaceExpression(
    scene: BallFaceScene,
    seed: Long,
    reroll: Int,
): BallFaceExpression {
    val track = remember(scene) { DirectorTrack(scene) }
    return track.choose(seed, reroll)
}

/** 记住上一次的抽取，保证同一场景内相邻两次不重复。 */
private class DirectorTrack(
    private val scene: BallFaceScene,
) {
    private var key: Pair<Long, Int>? = null
    private var chosen: BallFaceExpression? = null

    fun choose(
        seed: Long,
        reroll: Int,
    ): BallFaceExpression {
        val current = chosen
        val next = seed to reroll
        if (current != null && next == key) return current
        val picked =
            BallFaceDirector.pick(
                scene = scene,
                seed = BallFaceDirector.seedOf(seed, reroll),
                previous = current,
            )
        key = next
        chosen = picked
        return picked
    }
}

/** 绘制样式：形态、配色与会话数字。 */
private class FaceStyle(
    val variant: BallFaceVariant,
    val palette: FacePalette,
    val label: FaceLabel?,
    val measurer: TextMeasurer,
)

/** 会话数字：[inSatellite] 为真时替代卫星符号，否则画在卫星角标上。 */
private class FaceLabel(
    val text: String,
    val inSatellite: Boolean,
    val measurer: TextMeasurer,
)

@Composable
private fun BallFaceCanvas(
    expression: BallFaceExpression,
    style: FaceStyle,
    motion: Boolean,
    clock: FaceClock,
    satellitePaused: Boolean,
    gazeDirection: Offset?,
    modifier: Modifier,
) {
    val animator = remember { FaceAnimator() }
    // 目标在组合阶段设置，过渡的起点与时间在绘制阶段确定，二者都不触发重组。
    animator.setTarget(expression, style.label?.takeIf { it.inSatellite }?.text, animate = motion)
    Canvas(modifier) {
        val unit = minOf(size.width, size.height) / CANVAS_UNITS
        val t = if (motion) clock.seconds.value else STATIC_TIME_SEC
        val blinkAt = clock.blinkStart.value
        val blinking = motion && t >= blinkAt && t - blinkAt < BLINK_SHUT_SEC
        val base = animator.frameAt(t, motion, blinking, satellitePaused)
        // 拖动视线：方向 -1..1 映射到设计单位的视线偏移（最大 [DRAG_GAZE_UNITS]）。
        val frame =
            gazeDirection?.let {
                base.copy(gazeX = it.x * DRAG_GAZE_UNITS, gazeY = it.y * DRAG_GAZE_UNITS)
            } ?: base
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            scale(unit, unit, Offset.Zero)
        }) {
            drawFace(frame, style)
        }
    }
}

/**
 * 动画时钟：[seconds] 只在绘制阶段读取，不触发重组；[rerolls] 是轮换序号（每 [rerollSeconds] 秒加一），
 * 只在变化时重组。时钟只随 [enabled] 启动，不随场景变化重启，所以时间从不回退。
 */
internal class FaceClock(
    val seconds: State<Float>,
    val rerolls: State<Int>,
    /** 最近一次眨眼开始的时刻（秒）；从未眨眼时为 [NO_BLINK]。只在绘制阶段读取。 */
    val blinkStart: State<Float>,
)

@Composable
private fun rememberFaceClock(
    enabled: Boolean,
    rerollSeconds: Float,
    jitterSeed: Long?,
): FaceClock {
    val seconds = remember { mutableFloatStateOf(STATIC_TIME_SEC) }
    val rerolls = remember { mutableIntStateOf(0) }
    val blinkStart = remember { mutableFloatStateOf(NO_BLINK) }
    val latestReroll by rememberUpdatedState(rerollSeconds)
    LaunchedEffect(enabled, jitterSeed) {
        seconds.floatValue = STATIC_TIME_SEC
        rerolls.intValue = 0
        blinkStart.floatValue = NO_BLINK
        if (enabled) {
            val start = withFrameNanos { it }
            var jittered = 0
            var nextBoundary = jitterSeed?.let { BallFaceDirector.idleRerollSeconds(it, 1) } ?: 0f
            // 眨眼：首次在 2–6 秒后，之后每次闭眼结束再等 2–6 秒。
            var nextBlink = BallFaceDirector.blinkGapSec(Random.Default)
            while (true) {
                withFrameNanos { nanos ->
                    val elapsed = (nanos - start) / NANOS_PER_SECOND
                    seconds.floatValue = elapsed
                    if (elapsed >= nextBlink) {
                        blinkStart.floatValue = elapsed
                        nextBlink = elapsed + BLINK_SHUT_SEC + BallFaceDirector.blinkGapSec(Random.Default)
                    }
                    val count =
                        if (jitterSeed != null) {
                            // 随机间隔：边界逐个推进，序号相同的间隔由种子决定。
                            while (elapsed >= nextBoundary) {
                                jittered++
                                nextBoundary += BallFaceDirector.idleRerollSeconds(jitterSeed, jittered + 1)
                            }
                            jittered
                        } else {
                            val every = latestReroll
                            if (every > 0f) (elapsed / every).toInt() else 0
                        }
                    if (count != rerolls.intValue) rerolls.intValue = count
                }
            }
        }
    }
    return FaceClock(seconds, rerolls, blinkStart)
}

private fun DrawScope.drawFace(
    frame: BallFaceFrame,
    style: FaceStyle,
) {
    val palette = style.palette
    withTransform({
        translate(frame.shake, 0f)
        rotate(frame.tilt, Offset.Zero)
        scale(frame.breath, frame.breath, Offset.Zero)
    }) {
        // 圆盘或开口环只画一次，不参与过渡，避免交叉时变透。
        drawHead(style.variant, palette.body)
        val eyeColor = if (style.variant == BallFaceVariant.LINE) palette.body else palette.eye
        drawEye(frame.left, side = -1f, frame = frame, color = eyeColor)
        drawEye(frame.right, side = 1f, frame = frame, color = eyeColor)
        drawSatellite(frame.satellite, style)
    }
    frame.zPhase?.let { drawFloatingZ(it, style) }
}

/** 浮动 z：从头顶右侧升起，正弦淡入淡出；位置在球体外侧，不压在盘面上。 */
private fun DrawScope.drawFloatingZ(
    phase: Float,
    style: FaceStyle,
) {
    val alpha = sin(PI * phase).toFloat()
    drawLabelText(
        measurer = style.measurer,
        text = "z",
        sizeUnits = Z_UNITS,
        center = Offset(Z_X + Z_DX * phase, Z_Y - Z_DY * phase),
        color = style.palette.body.withAlpha(alpha),
    )
}

/** 球体：实心盘，或线条版的开口环（缺口在右上）。 */
private fun DrawScope.drawHead(
    variant: BallFaceVariant,
    color: Color,
) {
    when (variant) {
        BallFaceVariant.SOLID -> drawCircle(color = color, radius = DISC_RADIUS, center = Offset.Zero)
        BallFaceVariant.LINE ->
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = RING_SWEEP,
                useCenter = false,
                topLeft = Offset(-RING_CENTER_RADIUS, -RING_CENTER_RADIUS),
                size = Size(RING_CENTER_RADIUS * 2f, RING_CENTER_RADIUS * 2f),
                style = Stroke(width = RING_STROKE, cap = StrokeCap.Round),
            )
    }
}

private fun DrawScope.drawEye(
    eye: EyeFrame,
    side: Float,
    frame: BallFaceFrame,
    color: Color,
) {
    val x = side * EYE_DX + frame.gazeX + side * frame.gap
    val y = EYE_BASE_Y + frame.gazeY + eye.offsetY
    withTransform({
        translate(x, y)
        rotate(eye.rotation, Offset.Zero)
    }) {
        val from = eye.from
        if (from == null) {
            drawEyeForm(eye.form, side, color)
        } else {
            val fromBox = capsuleOf(from)
            val toBox = capsuleOf(eye.form)
            if (fromBox != null && toBox != null) {
                // 胶囊之间插值宽、高、圆角：眼睛逐帧变形，没有双影。
                drawCapsuleBox(lerpBox(fromBox, toBox, eye.mix), color)
            } else {
                // 笑眼、痛眼、叉眼等笔画形态之间交叉淡入。
                drawEyeForm(from, side, color.withAlpha(1f - eye.mix))
                drawEyeForm(eye.form, side, color.withAlpha(eye.mix))
            }
        }
    }
}

private fun DrawScope.drawEyeForm(
    form: BallEyeForm,
    side: Float,
    color: Color,
) {
    val box = capsuleOf(form)
    if (box != null) {
        drawCapsuleBox(box, color)
        return
    }
    when (form) {
        BallEyeForm.JOY ->
            drawPath(
                path = quadPath(-44f, 40f, 0f, -124f, 44f, 40f),
                color = color,
                style = Stroke(width = JOY_STROKE, cap = StrokeCap.Round),
            )
        BallEyeForm.PAIN ->
            drawPath(
                path = painPath(side),
                color = color,
                style = Stroke(width = PAIN_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        BallEyeForm.X ->
            drawPath(
                path = crossPath(),
                color = color,
                style = Stroke(width = PAIN_STROKE, cap = StrokeCap.Round),
            )
        else -> Unit
    }
}

/** 胶囊眼的尺寸（设计稿 FORM）；笔画形态返回 null。 */
private data class CapsuleBox(
    val w: Float,
    val h: Float,
    val r: Float,
)

private fun capsuleOf(form: BallEyeForm): CapsuleBox? =
    when (form) {
        BallEyeForm.OPEN -> CapsuleBox(105.6f, 199.2f, 52.8f)
        BallEyeForm.HALF -> CapsuleBox(105.6f, 100f, 50f)
        BallEyeForm.SQUINT -> CapsuleBox(100f, 56f, 28f)
        BallEyeForm.CLOSED -> CapsuleBox(96f, 30f, 15f)
        BallEyeForm.WIDE -> CapsuleBox(116f, 216f, 58f)
        BallEyeForm.ANGRY -> CapsuleBox(100f, 40f, 20f)
        else -> null
    }

private fun lerpBox(
    a: CapsuleBox,
    b: CapsuleBox,
    k: Float,
): CapsuleBox =
    CapsuleBox(
        w = a.w + (b.w - a.w) * k,
        h = a.h + (b.h - a.h) * k,
        r = a.r + (b.r - a.r) * k,
    )

private fun DrawScope.drawCapsuleBox(
    box: CapsuleBox,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-box.w / 2f, -box.h / 2f),
        size = Size(box.w, box.h),
        cornerRadius = CornerRadius(box.r),
    )
}

private fun DrawScope.drawSatellite(
    sat: SatelliteFrame,
    style: FaceStyle,
) {
    val palette = style.palette
    withTransform({
        translate(sat.x, sat.y)
        scale(sat.scale, sat.scale, Offset.Zero)
    }) {
        drawCircle(color = palette.body, radius = SATELLITE_RADIUS, center = Offset.Zero)
        drawSatelliteContent(sat, style, palette.glyph)
        val label = style.label
        if (label != null && !label.inSatellite) {
            drawCircle(color = palette.eye, radius = BADGE_RADIUS, center = BADGE_CENTER)
            drawLabelText(label.measurer, label.text, BADGE_DIGIT_UNITS, BADGE_CENTER, palette.body)
        }
    }
}

/** 卫星内容：内容切换时，旧内容和新内容交叉淡入。 */
private fun DrawScope.drawSatelliteContent(
    sat: SatelliteFrame,
    style: FaceStyle,
    color: Color,
) {
    val from = sat.contentFrom
    if (from == null) {
        drawContent(sat.content, style, color)
    } else {
        drawContent(from, style, color.withAlpha(1f - sat.contentMix))
        drawContent(sat.content, style, color.withAlpha(sat.contentMix))
    }
}

private fun DrawScope.drawContent(
    content: SatelliteContent,
    style: FaceStyle,
    color: Color,
) {
    when (content) {
        is SatelliteContent.Glyph -> drawGlyph(ballGlyphOf(content.symbol), color)
        is SatelliteContent.Digits ->
            style.label?.let { label -> drawLabelText(label.measurer, content.text, DIGIT_UNITS, Offset.Zero, color) }
    }
}

private fun DrawScope.drawGlyph(
    glyph: BallGlyph,
    color: Color,
) {
    withTransform({
        scale(glyph.scale, glyph.scale, Offset.Zero)
        translate(glyph.translateX, glyph.translateY)
    }) {
        glyph.parts.forEach { part -> drawGlyphPart(part, color) }
    }
}

/**
 * 数字：字号以设计单位给出，换算成 sp 后在当前缩放下得到设计单位的像素大小；在卫星局部坐标里居中绘制。
 */
private fun DrawScope.drawLabelText(
    measurer: TextMeasurer,
    text: String,
    sizeUnits: Float,
    center: Offset,
    color: Color,
) {
    val layout =
        measurer.measure(
            text = text,
            style =
                TextStyle(
                    color = color,
                    fontSize = (sizeUnits / density).sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
    )
}

private fun DrawScope.drawGlyphPart(
    part: BallGlyphPart,
    color: Color,
) {
    if (part.strokeWidth > 0f) {
        drawPath(
            path = part.path,
            color = color,
            style =
                Stroke(
                    width = part.strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashOf(part.dash),
                ),
        )
    } else {
        drawPath(path = part.path, color = color)
    }
}

private fun dashOf(intervals: List<Float>): PathEffect? =
    if (intervals.isEmpty()) null else PathEffect.dashPathEffect(intervals.toFloatArray())

private fun quadPath(
    sx: Float,
    sy: Float,
    cx: Float,
    cy: Float,
    ex: Float,
    ey: Float,
): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        moveTo(sx, sy)
        quadraticTo(cx, cy, ex, ey)
    }

private fun painPath(side: Float): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        if (side < 0f) {
            moveTo(-32f, -40f)
            lineTo(36f, 0f)
            lineTo(-32f, 40f)
        } else {
            moveTo(32f, -40f)
            lineTo(-36f, 0f)
            lineTo(32f, 40f)
        }
    }

private fun crossPath(): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        moveTo(-36f, -38f)
        lineTo(36f, 38f)
        moveTo(36f, -38f)
        lineTo(-36f, 38f)
    }

/** 配色：眼睛与球体的颜色、卫星符号色。 */
internal class FacePalette(
    val body: Color,
    val eye: Color,
    val glyph: Color,
)

internal fun facePaletteOf(
    paint: BallFacePaint,
    tint: Color?,
    scheme: ColorScheme,
    color: BallFaceColor,
): FacePalette {
    if (tint != null) return FacePalette(body = tint, eye = scheme.surface, glyph = scheme.surface)
    return when (paint) {
        BallFacePaint.THEME -> themePaletteOf(color, scheme)
        BallFacePaint.INK, BallFacePaint.MONO -> FacePalette(scheme.onSurface, scheme.surface, scheme.surface)
        BallFacePaint.CONTAINER ->
            FacePalette(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.onPrimaryContainer)
        BallFacePaint.INVERSE -> FacePalette(scheme.surfaceBright, scheme.primary, scheme.primary)
    }
}

/** THEME 配色：跟随主题色，或设置页的固定黑 / 白（眼睛与符号取反色，保证对比）。 */
private fun themePaletteOf(
    color: BallFaceColor,
    scheme: ColorScheme,
): FacePalette =
    when (color) {
        BallFaceColor.THEME -> FacePalette(scheme.primary, scheme.onPrimary, scheme.onPrimary)
        BallFaceColor.BLACK -> FacePalette(PetColors.Black, PetColors.White, PetColors.White)
        BallFaceColor.WHITE -> FacePalette(PetColors.White, PetColors.Black, PetColors.Black)
    }

private fun Color.withAlpha(alpha: Float): Color = copy(alpha = this.alpha * alpha)

/** 拖动时视线的最大偏移（设计单位）。 */
private const val DRAG_GAZE_UNITS: Float = 60f

/** 浮动 z 的起点与位移、字号（设计单位）。 */
private const val Z_X: Float = 430f
private const val Z_Y: Float = -430f
private const val Z_DX: Float = 60f
private const val Z_DY: Float = 220f
private const val Z_UNITS: Float = 150f

/** 静态落帧的时刻（秒）：与设计稿 `MOTION=false` 时的 t 一致。 */
internal const val STATIC_TIME_SEC: Float = 1.35f

/** 尚未眨眼时的起点：远早于任何时刻，使 `t - start` 始终不在闭眼窗口内。 */
private const val NO_BLINK: Float = -1_000f

private const val NANOS_PER_SECOND: Float = 1_000_000_000f
private const val MILLIS_PER_SECOND: Float = 1_000f
private const val CANVAS_UNITS: Float = 1408f
private const val DISC_RADIUS: Float = 512f
private const val RING_CENTER_RADIUS: Float = 459.2f
private const val RING_STROKE: Float = 105.6f
private const val RING_SWEEP: Float = 284.5f
private const val EYE_DX: Float = 144.3f
private const val EYE_BASE_Y: Float = BallFaceSpec.EYE_OFFSET_Y - 512f
private const val JOY_STROKE: Float = 50f
private const val PAIN_STROKE: Float = 44f
private const val SATELLITE_RADIUS: Float = 132f

/** 会话数字：超过 [MAX_DIGIT] 显示为 9+。 */
private const val MAX_DIGIT: Int = 9

/** 卫星内数字的字号（设计单位）。 */
private const val DIGIT_UNITS: Float = 118f

/** 卫星角标的圆心（卫星局部坐标）、半径与数字字号。 */
private val BADGE_CENTER: Offset = Offset(96f, -96f)
private const val BADGE_RADIUS: Float = 46f
private const val BADGE_DIGIT_UNITS: Float = 60f
