/**
 * 悬浮球视觉（docs/tech/08 §5.1，N185）：52dp 圆球、状态环、光晕、角标与中心图标。
 * 各状态差异由 [ballVisualSpec] 纯函数推导；动效含状态切换过渡（环色/环形态/图标 crossfade）、
 * 运行中呼吸光晕与不定环旋转、等待回答脉冲角标/光晕（全部受 `ui.appearance.motionEnabled`
 * 与 [LocalMotionEnabled] 控制，docs/tech/08 §6）。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.tone

/** 球直径（docs/tech/08 §5.1）。 */
internal val BALL_SIZE = 52.dp

/**
 * 光晕外扩（每侧，N217）：球窗口比球大出的部分——"进度环外的呼吸光晕"需要画在球外，
 * 窗口与球同大时光晕被窗口矩形裁剪，球后出现隐隐方框。外扩量即光晕的衰减半径，
 * 径向渐变在窗口边缘恰好归零，不产生可见边界。
 */
internal val HALO_PAD = 12.dp

/** 球窗口尺寸（球 + 光晕外扩；窗口 spec 用，[com.example.autop.feature.overlay.ballSpec]）。 */
internal val BALL_WINDOW_SIZE = BALL_SIZE + HALO_PAD * 2

/** 外圈进度环宽度（docs/tech/08 §5.1"外圈 3dp 进度环"）。 */
private val RING_WIDTH = 3.dp

/** 中心图标尺寸。 */
private val ICON_SIZE = 24.dp

/** 等待回答角标直径（docs/tech/08 §5.1"右上角 10dp 圆点角标"）。 */
private val BADGE_SIZE = 10.dp

/** 呼吸光晕 alpha 下限/上限（docs/tech/08 §5.1"alpha 0.2↔0.5"）。 */
private const val BREATH_ALPHA_MIN = 0.2f
private const val BREATH_ALPHA_MAX = 0.5f

/** 脉冲光晕 alpha 下限/上限（等待回答提醒，比呼吸更含蓄）。 */
private const val PULSE_HALO_ALPHA_MIN = 0.08f
private const val PULSE_HALO_ALPHA_MAX = 0.28f

/** 脉冲角标缩放上限与 alpha 下限。 */
private const val PULSE_SCALE_MAX = 1.35f
private const val PULSE_ALPHA_MIN = 0.55f

/** 不定进度圈的缺口角度（进度未知时画 300° 弧）。 */
private const val INDETERMINATE_SWEEP = 300f

/** 整圈环的扫过角度。 */
private const val RING_FULL_SWEEP = 360f

/** 一圈的角度。 */
private const val FULL_CIRCLE = 360f

/** 起始角（12 点方向）。 */
private const val START_ANGLE = -90f

/** 状态切换过渡时长（环色/环形态/图标，docs/tech/08 §6）。 */
private const val STATE_TRANSITION_MS = 250

/** 进度环比例变化的过渡时长。 */
private const val RING_TRANSITION_MS = 300

/** 脉冲（角标/光晕）周期。 */
private const val PULSE_PERIOD_MS = 1200

/** 不定环旋转周期。 */
private const val SPIN_PERIOD_MS = 1400

/** 球视觉参数的动画量（状态切换走颜色/扫过角过渡）。 */
@Immutable
private data class BallAnim(
    val ringColor: Color,
    val ringAlpha: Float,
    val sweep: Float,
    val iconAlpha: Float,
)

/** 球视觉：底圆 + 外圈 + 中心图标 + 角标（不含手势）；光晕画在球外的窗口余量里。 */
@Composable
internal fun BallVisual(
    uiState: BallUiState,
    modifier: Modifier = Modifier,
) {
    val motionOn = uiState.motionEnabled && LocalMotionEnabled.current
    val spec = ballVisualSpec(uiState.mode, motionOn)
    val anim = ballAnimOf(spec)
    Box(modifier = modifier.size(BALL_WINDOW_SIZE)) {
        StateHalo(haloKind = spec.haloKind, color = anim.ringColor, breathMs = uiState.breathMs)
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(BALL_SIZE),
        ) {
            BallBody()
            BallRing(
                color = anim.ringColor,
                sweep = anim.sweep,
                alpha = anim.ringAlpha,
                spin = spec.ringKind == RingKind.INDETERMINATE && motionOn,
            )
            Crossfade(
                targetState = iconOf(uiState.mode, (uiState.mode as? BallMode.Running)?.toolCategory),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .alpha(anim.iconAlpha),
                animationSpec = tween(STATE_TRANSITION_MS),
                label = "ball_icon",
            ) { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
            if (spec.badgeKind != BadgeKind.NONE) {
                StateBadge(
                    pulse = spec.badgeKind == BadgeKind.PULSE,
                    color = anim.ringColor,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/** 状态光晕：呼吸 / 脉冲 / 无。 */
@Composable
private fun BoxScope.StateHalo(
    haloKind: HaloKind,
    color: Color,
    breathMs: Long,
) {
    when (haloKind) {
        HaloKind.BREATH ->
            OscillatingHalo(
                color = color,
                alphaMin = BREATH_ALPHA_MIN,
                alphaMax = BREATH_ALPHA_MAX,
                periodMs = breathMs,
            )
        HaloKind.PULSE ->
            OscillatingHalo(
                color = color,
                alphaMin = PULSE_HALO_ALPHA_MIN,
                alphaMax = PULSE_HALO_ALPHA_MAX,
                periodMs = PULSE_PERIOD_MS.toLong(),
            )
        HaloKind.NONE -> Unit
    }
}

/** 状态视觉 → 动画量（N185）：环色/图标透明度过渡，环扫过角 0↔整圈↔缺口弧↔进度弧。 */
@Composable
private fun ballAnimOf(spec: BallVisualSpec): BallAnim {
    val ringColor by animateColorAsState(
        targetValue = spec.ringTone?.let { LocalSemanticColors.current.tone(it).foreground } ?: Color.Transparent,
        animationSpec = tween(STATE_TRANSITION_MS),
        label = "ball_ring_color",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (spec.ringKind == RingKind.NONE) 0f else 1f,
        animationSpec = tween(STATE_TRANSITION_MS),
        label = "ball_ring_alpha",
    )
    val sweep by animateFloatAsState(
        targetValue =
            when (spec.ringKind) {
                RingKind.NONE -> 0f
                RingKind.FULL -> RING_FULL_SWEEP
                RingKind.INDETERMINATE -> INDETERMINATE_SWEEP
                RingKind.PROGRESS -> FULL_CIRCLE * (spec.ringProgress ?: 0f)
            },
        animationSpec = tween(RING_TRANSITION_MS),
        label = "ball_ring_sweep",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = spec.iconAlpha,
        animationSpec = tween(STATE_TRANSITION_MS),
        label = "ball_icon_alpha",
    )
    return BallAnim(ringColor, ringAlpha, sweep, iconAlpha)
}

/** 底圆：surfaceContainerHigh α0.9（docs/tech/08 §5.1）。 */
@Composable
private fun BoxScope.BallBody() {
    Box(
        Modifier
            .matchParentSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), CircleShape),
    )
}

/**
 * 呼吸/脉冲光晕（N185：运行中 alpha 起伏、等待回答含蓄脉冲；动效关闭时不上屏）。
 * N217：径向渐变替代 blur——blur 的光晕被窗口矩形裁剪，球后留下方框；
 * 渐变半径即窗口内切圆，边缘 alpha 归零，露出的一圈就是"进度环外的光晕"。
 */
@Composable
private fun BoxScope.OscillatingHalo(
    color: Color,
    alphaMin: Float,
    alphaMax: Float,
    periodMs: Long,
) {
    val transition = rememberInfiniteTransition(label = "ball_halo")
    val alpha by transition.animateFloat(
        initialValue = alphaMin,
        targetValue = alphaMax,
        animationSpec =
            infiniteRepeatable(
                animation = tween(periodMs.coerceAtLeast(1L).toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "ball_halo_alpha",
    )
    Canvas(Modifier.matchParentSize().alpha(alpha)) {
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to color,
                    1f to color.copy(alpha = 0f),
                    center = center,
                    radius = size.minDimension / 2,
                ),
        )
    }
}

/**
 * 状态外圈（N185）：[sweep] 由 [ballAnimOf] 过渡而来（进度增长/状态切换都走颜色与形态过渡）；
 * [spin] 为不定环旋转（仅动效开启）。
 */
@Composable
private fun BoxScope.BallRing(
    color: Color,
    sweep: Float,
    alpha: Float,
    spin: Boolean,
) {
    if (alpha <= 0f) return
    val angleOffset = spinAngle(spin)
    Canvas(Modifier.matchParentSize().alpha(alpha)) {
        val stroke = Stroke(width = RING_WIDTH.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(
            color = color,
            startAngle = START_ANGLE + angleOffset,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

/** 不定环旋转角度（动效关闭时恒 0，弧静止）。 */
@Composable
private fun spinAngle(spin: Boolean): Float =
    if (spin) {
        val transition = rememberInfiniteTransition(label = "ball_spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = FULL_CIRCLE,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(SPIN_PERIOD_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "ball_spin_angle",
        )
        angle
    } else {
        0f
    }

/** 右上角角标（N185）：等待回答为脉冲圆点（[pulse] 关闭时静态圆点，提醒不消失）。 */
@Composable
private fun StateBadge(
    pulse: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (pulse) {
        val transition = rememberInfiniteTransition(label = "ball_pulse_badge")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_SCALE_MAX,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(PULSE_PERIOD_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "ball_pulse_badge_scale",
        )
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_ALPHA_MIN,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(PULSE_PERIOD_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "ball_pulse_badge_alpha",
        )
        Box(
            modifier
                .size(BADGE_SIZE)
                .scale(scale)
                .alpha(alpha)
                .background(color, CircleShape),
        )
    } else {
        Box(
            modifier
                .size(BADGE_SIZE)
                .background(color, CircleShape),
        )
    }
}

/** 中心图标（docs/tech/08 §5.1：运行中换成当前工具类别图标）。 */
internal fun iconOf(
    mode: BallMode,
    toolCategory: ToolCategory?,
): ImageVector =
    when (mode) {
        is BallMode.Running ->
            when (toolCategory) {
                ToolCategory.GESTURE -> Icons.Outlined.TouchApp
                ToolCategory.TEXT -> Icons.Outlined.Keyboard
                ToolCategory.VISION -> Icons.Outlined.Visibility
                ToolCategory.APP -> Icons.Outlined.Apps
                ToolCategory.OTHER, null -> AutopIcons.Orbit
            }
        BallMode.Paused -> Icons.Outlined.Pause
        BallMode.Blocked -> Icons.Outlined.Block
        BallMode.Idle, BallMode.WaitingAnswer, BallMode.AccessibilityLost -> AutopIcons.Orbit
    }
