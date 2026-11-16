/**
 * 统一输入区（对话、AI 创建任务、AI 创建流程共用）：可选的顶部行（模型选择、开关等）+ 输入栏。
 * 输入栏左侧可放附件入口（[leading]），右侧只有一个按钮：发送、停止、语音，或没有（[ComposerTrailing]）。
 * 高度、间距、按钮尺寸都由这里统一（见 [AutopDimens] 与紧凑图标按钮），各页面不再自行调整。
 */
package com.example.autop.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/** 发送与停止（或语音）互换的淡入淡出时长（毫秒）。 */
private const val SWAP_MS: Int = 150

/**
 * 统一输入区。
 *
 * @param top 输入框上方的一行（如模型选择、只聊天开关）；null 时不显示。
 * @param leading 输入框左侧的入口（如「＋」附件菜单）。
 * @param trailing 尾部按钮模式。
 * @param sendEnabled 发送键是否可用（不可用时降透明，点击仍交给调用方校验）。
 * @param listening 语音输入中（麦克风为选中态）。
 * @param onVoice 点麦克风（为 null 时不显示语音键）；开始或结束由调用方决定。
 * @param focusRequester 输入框焦点。
 */
@Suppress("LongParameterList") // 统一输入区的插槽与回调，不可再减
@Composable
public fun AutopComposer(
    value: String,
    onValueChange: (String) -> Unit,
    spec: InputBarSpec,
    modifier: Modifier = Modifier,
    top: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: ComposerTrailing = ComposerTrailing.SEND,
    sendEnabled: Boolean = true,
    listening: Boolean = false,
    voiceLevel: Float = 0f,
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onVoice: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val spacing = LocalSpacing.current
    val inputModifier =
        Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm)
            .let { base -> focusRequester?.let { base.focusRequester(it) } ?: base }
    Column(modifier = modifier.fillMaxWidth()) {
        if (top != null) {
            // 芯片行与输入栏之间已有 sm，上方补同样的间距，避免贴到组件上边缘。
            Row(
                modifier = Modifier.padding(top = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                content = top,
            )
        }
        AutopInputBar(
            value = value,
            onValueChange = onValueChange,
            spec = spec,
            modifier = inputModifier,
            leading = leading,
            trailing = {
                ComposerTrailingButton(
                    mode = trailing,
                    sendEnabled = sendEnabled,
                    listening = listening,
                    voiceLevel = voiceLevel,
                    onSend = onSend,
                    onStop = onStop,
                    onVoice = onVoice,
                )
            },
        )
    }
}

/** 尾部按钮：模式切换时淡入淡出，布局不跳动。 */
@Composable
private fun ComposerTrailingButton(
    mode: ComposerTrailing,
    sendEnabled: Boolean,
    listening: Boolean,
    voiceLevel: Float,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: (() -> Unit)?,
) {
    val duration = if (LocalMotionEnabled.current) SWAP_MS else 0
    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
        label = "autop_composer_trailing",
    ) { target ->
        when (target) {
            ComposerTrailing.NONE -> Unit
            ComposerTrailing.STOP ->
                StopRunningButton(
                    onClick = onStop,
                    contentDescription = stringResource(R.string.core_ui_composer_stop),
                )
            ComposerTrailing.VOICE ->
                if (onVoice != null) {
                    if (listening) {
                        ListeningMicButton(
                            level = voiceLevel,
                            onClick = onVoice,
                            contentDescription = stringResource(R.string.core_ui_composer_voice_stop),
                        )
                    } else {
                        AutopIconButton(
                            icon = AutopIcons.Mic,
                            contentDescription = stringResource(R.string.core_ui_composer_voice),
                            onClick = onVoice,
                            compact = true,
                        )
                    }
                }
            ComposerTrailing.SEND ->
                AutopIconButton(
                    icon = AutopIcons.Send,
                    contentDescription = stringResource(R.string.core_ui_composer_send),
                    onClick = onSend,
                    compact = true,
                    modifier = if (sendEnabled) Modifier else Modifier.alpha(AutopAlpha.DISABLED_CONTENT),
                )
        }
    }
}

/**
 * 运行中的停止按钮：36dp 圆形底，外圈有一段红色弧线持续转动，表示正在运行、可以停止。
 * 关闭动效（[LocalMotionEnabled]）时只显示静态的停止图标。
 */
@Composable
private fun StopRunningButton(
    onClick: () -> Unit,
    contentDescription: String,
) {
    val motion = LocalMotionEnabled.current
    val angle by rememberInfiniteTransition(label = "stop_arc").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = STOP_ARC_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "stop_arc_angle",
    )
    val color = MaterialTheme.colorScheme.error
    val arcWidth = AutopDimens.stopArcWidth
    Box(
        modifier =
            Modifier
                .size(STOP_BUTTON_SIZE)
                .semantics { this.contentDescription = contentDescription }
                .clip(CircleShape)
                .background(color.copy(alpha = AutopAlpha.STOP_FILL))
                .clickable(onClick = onClick)
                .drawBehind {
                    if (motion) {
                        val stroke = arcWidth.toPx()
                        drawArc(
                            color = color,
                            startAngle = angle,
                            sweepAngle = STOP_ARC_SWEEP,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AutopIcons.Stop,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(IconSize.Sm.scaled()),
        )
    }
}

private val STOP_BUTTON_SIZE: Dp = 36.dp
private const val STOP_ARC_PERIOD_MS: Int = 1_100
private const val STOP_ARC_SWEEP: Float = 110f
