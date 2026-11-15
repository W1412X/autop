/**
 * 图标按钮与图标动作块（docs/tech/08 §2）。
 *
 * 图标着色回落 [LocalContentColor]（LimeTV 修过的深色模式黑图标问题）：
 * 需要自定义色时用 `CompositionLocalProvider(LocalContentColor provides …)` 包裹。
 */
package com.example.autop.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import kotlin.math.PI
import kotlin.math.sin

/**
 * 图标按钮：默认为无底色图标按钮（[compact] 时为较小尺寸）。[selected] 为录音等进行中的状态：
 * 中间是 primary 半透明圆底，外圈有层层向外扩散、逐渐淡出的光环（关闭动效时不显示光环）。
 */
@Composable
public fun AutopIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    compact: Boolean = false,
) {
    val size = Modifier.size(if (compact) CompactButtonSize else AutopDimens.iconButtonSize)
    val iconSize = if (compact) IconSize.Sm.scaled() else IconSize.Md.scaled()
    val iconTint = LocalContentColor.current
    if (selected) {
        Box(
            modifier = modifier.then(size).voiceHalo(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            FilledIconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = AutopAlpha.CHIP_SELECTED_FILL),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = iconTint,
                )
            }
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier.then(size)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = iconTint,
            )
        }
    }
}

private val CompactButtonSize = 36.dp

/** 图标在上、文字在下的动作块；底 surfaceVariant α0.55，选中 primaryContainer。 */
@Composable
public fun IconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val spacing = LocalSpacing.current
    val container =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL)
        }
    Column(
        modifier =
            modifier
                .background(color = container, shape = MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Md.scaled()),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 外圈光环：多层同心圆，半径逐层向外扩大、透明度逐层变淡，循环出现。 */
@Composable
private fun Modifier.voiceHalo(color: Color): Modifier {
    if (!LocalMotionEnabled.current) return this
    val phase by rememberInfiniteTransition(label = "voice_halo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = HALO_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "voice_halo_phase",
    )
    return drawBehind {
        val base = size.minDimension / 2f
        repeat(HALO_RINGS) { ring ->
            val t = (phase + ring.toFloat() / HALO_RINGS) % 1f
            drawCircle(
                color = color.copy(alpha = sin(PI.toFloat() * t) * AutopAlpha.HALO_MAX),
                radius = base * (HALO_INNER_FRACTION + (1f - HALO_INNER_FRACTION) * t),
            )
        }
    }
}

private const val HALO_RINGS: Int = 3
private const val HALO_INNER_FRACTION: Float = 0.6f
private const val HALO_PERIOD_MS: Int = 1_600

/** 录音中的麦克风按钮：圆形底与内圈光环不变，中间的麦克风图标换成随音量跳动的音波。点击即停止录音。 */
@Composable
internal fun ListeningMicButton(
    level: Float,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .size(CompactButtonSize)
                .semantics { this.contentDescription = contentDescription }
                .voiceHalo(primary)
                .clip(CircleShape)
                .background(primary.copy(alpha = AutopAlpha.CHIP_SELECTED_FILL))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VoiceWave(level = level, barWidth = LISTENING_BAR_WIDTH, waveHeight = LISTENING_WAVE_HEIGHT)
    }
}

private val LISTENING_BAR_WIDTH: Dp = 2.dp
private val LISTENING_WAVE_HEIGHT: Dp = 18.dp
