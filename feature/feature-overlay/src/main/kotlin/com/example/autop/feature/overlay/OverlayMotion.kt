/**
 * 悬浮层动效的共用部分：呼吸缓动与呼吸进度。
 *
 * 呼吸用 ease-in-out 正弦曲线（两端慢、中间快），线性往返会在最亮/最暗处急转，看起来像机械闪烁。
 * 进度以 [State] 返回，调用方只在绘制阶段（`drawBehind` / `graphicsLayer {}`）读取它，
 * 动画每帧只触发重绘，不触发重组。
 */
package com.example.autop.feature.overlay

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/** ease-in-out sine 的三次贝塞尔近似。 */
internal val BreathEasing: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * 匀速旋转角度（0..360 度，循环）。[periodMs] 是转一圈的时长；[enabled] 为 false 时不启动动画，恒为 0。
 * 与 [rememberBreath] 一样以 [State] 返回，只应在绘制阶段读取。
 */
@Composable
internal fun rememberRotation(
    periodMs: Long,
    enabled: Boolean,
): State<Float> =
    if (enabled) {
        rememberInfiniteTransition(label = "rotation").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = periodMs.coerceAtLeast(1L).toInt(), easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "rotation_angle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

/** 一圈的角度。 */
private const val FULL_TURN: Float = 360f

/** 动效关闭时的静态呼吸进度：取中间值，亮度与范围居中。 */
private const val STATIC_BREATH: Float = 0.5f

/**
 * 呼吸进度（0..1，来回往复）。[halfPeriodMs] 是从最暗到最亮的时长，完整一次呼吸为其两倍。
 * [enabled] 为 false 时不启动动画，返回固定的中间值。
 */
@Composable
internal fun rememberBreath(
    halfPeriodMs: Long,
    enabled: Boolean,
): State<Float> =
    if (enabled) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = halfPeriodMs.coerceAtLeast(1L).toInt(), easing = BreathEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "breath_progress",
        )
    } else {
        remember { mutableFloatStateOf(STATIC_BREATH) }
    }
