/**
 * 统一输入区（对话、AI 创建任务、AI 创建流程共用）：可选的顶部行（模型选择、开关等）+ 输入栏。
 * 输入栏左侧可放附件入口（[leading]），右侧只有一个按钮：发送、停止、语音，或没有（[ComposerTrailing]）。
 * 高度、间距、按钮尺寸都由这里统一（见 [AutopDimens] 与紧凑图标按钮），各页面不再自行调整。
 */
package com.example.autop.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing

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
            Row(verticalAlignment = Alignment.CenterVertically, content = top)
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
                AutopIconButton(
                    icon = AutopIcons.Stop,
                    contentDescription = stringResource(R.string.core_ui_composer_stop),
                    onClick = onStop,
                    compact = true,
                )
            ComposerTrailing.VOICE ->
                if (onVoice != null) {
                    AutopIconButton(
                        icon = AutopIcons.Mic,
                        contentDescription =
                            stringResource(
                                if (listening) {
                                    R.string.core_ui_composer_voice_stop
                                } else {
                                    R.string.core_ui_composer_voice
                                },
                            ),
                        onClick = onVoice,
                        compact = true,
                        selected = listening,
                    )
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
