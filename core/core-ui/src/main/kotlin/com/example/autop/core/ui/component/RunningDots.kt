package com.example.autop.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.LocalMotionEnabled
import kotlin.math.abs

/**
 * 运行中的三点跳动图标（对话列表里运行中的对话使用）：三个点依次向上跳动。
 * 关闭动效（[LocalMotionEnabled]）时三点静止。
 */
@Composable
public fun RunningDots(modifier: Modifier = Modifier) {
    val motion = LocalMotionEnabled.current
    val phase by rememberInfiniteTransition(label = "running_dots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = DOTS_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "running_dots_phase",
    )
    Row(
        modifier = modifier.size(ICON_BOX),
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOT_COUNT) { index ->
            // 三角波：每个点在一个周期内升起再落下，相位错开
            val t = if (motion) (phase + index * DOT_PHASE_STEP) % 1f else 0f
            val lift = 1f - abs(2f * t - 1f)
            Box(
                modifier =
                    Modifier
                        .offset(y = DOT_LIFT * -lift)
                        .size(DOT_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val DOT_COUNT: Int = 3
private const val DOTS_PERIOD_MS: Int = 900
private const val DOT_PHASE_STEP: Float = 0.2f
private val ICON_BOX: Dp = 28.dp
private val DOT_SIZE: Dp = 5.dp
private val DOT_GAP: Dp = 3.dp
private val DOT_LIFT: Dp = 4.dp
