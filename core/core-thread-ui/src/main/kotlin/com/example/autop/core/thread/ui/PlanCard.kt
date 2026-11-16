/**
 * 计划卡统一组件：对话页时间线（[TimelineRow]）与进度悬浮窗（`ProgressRows`）共用同一份实现，
 * 视觉稿见 docs/design/plan-ui-mocks/bd-fusion-v2.html。
 *
 * - 每项前是状态图标：待办空心圆、进行中淡环加旋转弧线、完成实心圆加勾、放弃灰圆加 ×。颜色取自语义色（[StatusTone]），与步骤行一致。
 * - 动效只在状态切换时播放：进行中弧线匀速旋转；完成时圆盘弹出，勾号描边。进入页面时已是终态则直接定格；
 *   系统“减少动画”（[LocalMotionEnabled] 为假）时不做动效。
 * - 单行截断：超出一行的文字省略，点击该行展开为完整文本，再点收起。
 */
package com.example.autop.core.thread.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.core.thread.ui.R
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.core.ui.theme.tone

/** 图标在 24 单位视窗内的几何（与设计稿一致，按实际尺寸缩放）。 */
private const val ICON_UNITS: Float = 24f
private const val RING_RADIUS_UNITS: Float = 9f
private const val STROKE_UNITS: Float = 2f
private const val ARC_STROKE_UNITS: Float = 2.4f
private const val CHECK_STROKE_UNITS: Float = 2.6f
private const val ARC_START_DEGREES: Float = -90f
private const val ARC_SWEEP_DEGREES: Float = 90f
private const val CROSS_NEAR_UNITS: Float = 9f
private const val CROSS_FAR_UNITS: Float = 15f

/** 淡化系数：进行中的外环（相对主色），待办、放弃的灰色圆环。 */
private const val RING_RUNNING_ALPHA: Float = 0.35f
private const val RING_MUTED_ALPHA: Float = 0.6f

/** 完成圆盘的起始缩放（弹出到 1）。 */
private const val DONE_START_SCALE: Float = 0.4f

/** 动效时长（毫秒）：旋转一圈、圆盘弹出、勾号描边及其延迟。 */
private const val SPIN_MS: Int = 1100
private const val POP_MS: Int = 320
private const val DRAW_MS: Int = 340
private const val DRAW_DELAY_MS: Int = 80

/** 圆盘弹出的缓动，控制点超过 1 时产生轻微回弹。 */
private val POP_EASING = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.4f)

/** 勾号的三个顶点（24 单位视窗内）。 */
private val CHECK_START = Offset(7.5f, 12.5f)
private val CHECK_MID = Offset(10.5f, 15.5f)
private val CHECK_END = Offset(16.5f, 9f)

/** 计划卡。[explanation] 为本次更新的说明（update_plan 的 explanation），没有则不显示。 */
@Composable
public fun PlanCardView(
    items: List<PlanItem>,
    explanation: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ).padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.thread_plan_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        explanation?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 计划项之间不加额外间距：行间距只来自每行上下各 xs 的内边距（行高约 28dp）。
        // 用 index 作 key：计划是覆盖式更新，同一位置的项沿用状态，动效只在状态真正变化时播放。
        Column {
            items.forEachIndexed { index, plan -> key(index) { PlanLine(plan = plan) } }
        }
    }
}

@Composable
private fun PlanLine(plan: PlanItem) {
    val spacing = LocalSpacing.current
    val motion = LocalMotionEnabled.current
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    // 图标框与首行等高，图标在首行上居中；展开为多行时图标仍对齐首行。
    val iconFrame: Dp =
        if (style.lineHeight.isSpecified) with(density) { style.lineHeight.toDp() } else IconSize.Sm.scaled()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    val clickable = overflowing || expanded
    val running = plan.status == PlanStatus.IN_PROGRESS
    val runningTone = LocalSemanticColors.current.tone(StatusTone.RUNNING)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (running) Modifier.background(runningTone.container, MaterialTheme.shapes.small) else Modifier)
                .then(if (clickable) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = spacing.xs, vertical = spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.height(iconFrame), contentAlignment = Alignment.Center) {
            PlanStatusIcon(status = plan.status, motion = motion)
        }
        Text(
            text = plan.step,
            style = style,
            color =
                when (plan.status) {
                    PlanStatus.PENDING -> MaterialTheme.colorScheme.onSurface
                    PlanStatus.IN_PROGRESS -> runningTone.foreground
                    PlanStatus.DONE -> muted
                    PlanStatus.DROPPED -> muted.copy(alpha = RING_MUTED_ALPHA)
                },
            fontWeight = if (running) FontWeight.SemiBold else null,
            textDecoration = if (plan.status == PlanStatus.DROPPED) TextDecoration.LineThrough else null,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflowing = it.hasVisualOverflow },
            modifier = Modifier.weight(1f).padding(start = spacing.sm),
        )
    }
}

/** 状态图标。完成动效由 [Animatable] 驱动，只在状态变为完成时播放。 */
@Composable
private fun PlanStatusIcon(
    status: PlanStatus,
    motion: Boolean,
) {
    val iconSize = IconSize.Sm.scaled()
    val tones = LocalSemanticColors.current
    val doneColor = tones.tone(StatusTone.SUCCEEDED).foreground
    val runningColor = tones.tone(StatusTone.RUNNING).foreground
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = RING_MUTED_ALPHA)
    val checkColor = MaterialTheme.colorScheme.onPrimary
    // 初始即为完成态时直接定格；之后状态变为完成才从 0 播放。
    val pop = remember { Animatable(if (status == PlanStatus.DONE) 1f else 0f) }
    val draw = remember { Animatable(if (status == PlanStatus.DONE) 1f else 0f) }
    LaunchedEffect(status, motion) {
        when {
            status != PlanStatus.DONE -> {
                pop.snapTo(0f)
                draw.snapTo(0f)
            }
            !motion -> {
                pop.snapTo(1f)
                draw.snapTo(1f)
            }
            else -> {
                pop.animateTo(1f, tween(durationMillis = POP_MS, easing = POP_EASING))
                draw.animateTo(
                    1f,
                    tween(durationMillis = DRAW_MS, delayMillis = DRAW_DELAY_MS, easing = LinearOutSlowInEasing),
                )
            }
        }
    }
    // 只有进行中的图标需要旋转角度；其他状态不创建无限动画。
    val spin: State<Float>? = if (status == PlanStatus.IN_PROGRESS && motion) rememberSpinDegrees() else null
    Canvas(modifier = Modifier.size(iconSize)) {
        val unit = size.minDimension / ICON_UNITS
        when (status) {
            PlanStatus.PENDING -> drawRing(unit, mutedColor)
            PlanStatus.IN_PROGRESS -> {
                drawRing(unit, runningColor.copy(alpha = RING_RUNNING_ALPHA))
                drawArcAt(unit, runningColor, spin?.value ?: 0f)
            }
            PlanStatus.DONE -> {
                drawDoneDisc(unit, doneColor, pop.value)
                drawCheck(unit, checkColor, draw.value.coerceIn(0f, 1f))
            }
            PlanStatus.DROPPED -> {
                drawRing(unit, mutedColor)
                drawCross(unit, mutedColor)
            }
        }
    }
}

@Composable
private fun rememberSpinDegrees(): State<Float> =
    rememberInfiniteTransition(label = "plan_spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SPIN_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "plan_spin_degrees",
    )

private fun DrawScope.drawRing(
    unit: Float,
    color: Color,
) {
    drawCircle(
        color = color,
        radius = RING_RADIUS_UNITS * unit,
        center = center,
        style = Stroke(width = STROKE_UNITS * unit),
    )
}

/** 旋转弧线：长 90 度，起点在顶部，[degrees] 为旋转角度。 */
private fun DrawScope.drawArcAt(
    unit: Float,
    color: Color,
    degrees: Float,
) {
    val radius = RING_RADIUS_UNITS * unit
    drawArc(
        color = color,
        startAngle = ARC_START_DEGREES + degrees,
        sweepAngle = ARC_SWEEP_DEGREES,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = ARC_STROKE_UNITS * unit, cap = StrokeCap.Round),
    )
}

/** 完成圆盘：从 [DONE_START_SCALE] 弹到满尺寸，透明度跟随 [pop]。 */
private fun DrawScope.drawDoneDisc(
    unit: Float,
    color: Color,
    pop: Float,
) {
    val scale = DONE_START_SCALE + (1f - DONE_START_SCALE) * pop
    drawCircle(
        color = color,
        radius = RING_RADIUS_UNITS * unit * scale,
        center = center,
        alpha = pop.coerceIn(0f, 1f),
    )
}

/** 勾号按 [progress]（0..1）逐段描边：先画短边，再画长边。进度为 0 时不画，避免残留圆点。 */
private fun DrawScope.drawCheck(
    unit: Float,
    color: Color,
    progress: Float,
) {
    if (progress <= 0f) return
    val start = CHECK_START * unit
    val mid = CHECK_MID * unit
    val end = CHECK_END * unit
    val firstLen = (mid - start).getDistance()
    val secondLen = (end - mid).getDistance()
    val drawn = progress * (firstLen + secondLen)
    val stroke = CHECK_STROKE_UNITS * unit
    if (drawn <= firstLen) {
        drawLine(color, start, start + (mid - start) * (drawn / firstLen), stroke, StrokeCap.Round)
    } else {
        drawLine(color, start, mid, stroke, StrokeCap.Round)
        drawLine(color, mid, mid + (end - mid) * ((drawn - firstLen) / secondLen), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.drawCross(
    unit: Float,
    color: Color,
) {
    val stroke = STROKE_UNITS * unit
    val near = CROSS_NEAR_UNITS * unit
    val far = CROSS_FAR_UNITS * unit
    drawLine(color, Offset(near, near), Offset(far, far), stroke, StrokeCap.Round)
    drawLine(color, Offset(far, near), Offset(near, far), stroke, StrokeCap.Round)
}
