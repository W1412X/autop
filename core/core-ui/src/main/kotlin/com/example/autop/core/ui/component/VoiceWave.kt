package com.example.autop.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalMotionEnabled
import kotlin.math.PI
import kotlin.math.sin

/**
 * 录音音波：[level] 为 0..1 的音量。无声时保持轻微起伏（[IDLE_AMPLITUDE]），音量越大起伏越明显。
 * 关闭动效（[LocalMotionEnabled]）时竖条静止在中间高度。
 */
@Composable
public fun VoiceWave(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val smoothLevel by animateFloatAsState(targetValue = level.coerceIn(0f, 1f), label = "voice_level")
    val motion = LocalMotionEnabled.current
    val phase by rememberInfiniteTransition(label = "voice_wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = WAVE_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "voice_phase",
    )
    val amplitude = IDLE_AMPLITUDE + (1f - IDLE_AMPLITUDE) * smoothLevel
    Row(
        modifier = modifier.height(AutopDimens.voiceWaveHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AutopDimens.voiceBarGap),
    ) {
        repeat(BAR_COUNT) { index ->
            val wobble =
                if (motion) {
                    (sin(2 * PI * (phase + index * BAR_PHASE_STEP)).toFloat() + 1f) / 2f
                } else {
                    STATIC_WOBBLE
                }
            val fraction = MIN_FRACTION + (1f - MIN_FRACTION) * amplitude * wobble
            Box(
                modifier =
                    Modifier
                        .width(AutopDimens.voiceBarWidth)
                        .fillMaxHeight(fraction)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

private const val BAR_COUNT = 5
private const val WAVE_PERIOD_MS = 900
private const val BAR_PHASE_STEP = 0.2
private const val MIN_FRACTION = 0.2f
private const val IDLE_AMPLITUDE = 0.2f
private const val STATIC_WOBBLE = 0.5f
