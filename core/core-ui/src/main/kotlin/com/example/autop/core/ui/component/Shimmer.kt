/**
 * shimmer 骨架屏（docs/tech/08 §2）：surfaceVariant 底 + surface α0.7 高光线性扫过，
 * tween 线性无限循环；`LocalMotionEnabled = false` 时静态。
 */
package com.example.autop.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing

/** shimmer 循环时长默认值（对应 `ui.motion.shimmerMs`，调用方传配置值覆盖）。 */
public const val DEFAULT_SHIMMER_MS: Int = 900

/** 高光扫过的渐变宽度（相对组件宽度的比例分母）。 */
private const val GRADIENT_WIDTH_DIVISOR: Float = 2f

/** 骨架条占行宽比例。 */
private const val BAR_FRACTION_WIDE: Float = 0.6f
private const val BAR_FRACTION_NARROW: Float = 0.35f

/** shimmer 高光修饰符（叠在骨架块上）。 */
@Composable
public fun Modifier.shimmer(shimmerMs: Int = DEFAULT_SHIMMER_MS): Modifier {
    if (!LocalMotionEnabled.current) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = shimmerMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "shimmerProgress",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface.copy(alpha = AutopAlpha.SHIMMER_HIGHLIGHT)
    return this.drawBehind {
        val gradientWidth = size.width / GRADIENT_WIDTH_DIVISOR
        val x = progress * (size.width + gradientWidth) - gradientWidth
        drawRect(
            brush =
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(x - gradientWidth, 0f),
                    end = Offset(x, size.height),
                ),
        )
    }
}

/** 骨架行：左侧 48dp 块 + 两行条，shimmer 扫光。 */
@Composable
public fun SkeletonRow(
    modifier: Modifier = Modifier,
    shimmerMs: Int = DEFAULT_SHIMMER_MS,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(AutopDimens.listThumb)
                    .clip(MaterialTheme.shapes.medium)
                    .shimmer(shimmerMs),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SkeletonBar(fraction = BAR_FRACTION_WIDE, shimmerMs = shimmerMs)
            SkeletonBar(fraction = BAR_FRACTION_NARROW, shimmerMs = shimmerMs)
        }
    }
}

@Composable
private fun SkeletonBar(
    fraction: Float,
    shimmerMs: Int,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(fraction)
                .height(LocalSpacing.current.sm)
                .clip(MaterialTheme.shapes.extraSmall)
                .shimmer(shimmerMs),
    )
}
