/**
 * 输入区（docs/tech/08 §4.2 InputArea）："附带当前屏幕"Chip + 输入栏；
 * 尾部空闲显示发送，运行中显示停止（error 色）+ 发送（steer）。
 * `ui.chat.backgroundOnSend` 的让屏行为由 ViewModel 经 navEvents 交给 app。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

/**
 * 输入区。
 *
 * @param state 页面状态（输入文本、运行中、附屏幕开关、无障碍连接）。
 * @param onAction 用户操作回调。
 * @param focusRequester 输入框焦点（AskCard 的"自己输入"聚焦用）。
 */
@Composable
internal fun InputArea(
    state: ChatThreadUiState,
    onAction: (ChatThreadAction) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val enabled = state.accessibilityConnected
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge),
    ) {
        AutopChip(
            text = stringResource(R.string.thread_attach_screen),
            selected = state.attachScreen,
            onClick = { onAction(ChatThreadAction.ToggleAttachScreen) },
        )
        AutopInputBar(
            value = state.input,
            onValueChange = { text -> if (enabled) onAction(ChatThreadAction.SetInput(text)) },
            spec =
                InputBarSpec(
                    placeholder =
                        stringResource(
                            if (state.running) {
                                R.string.thread_input_hint_running
                            } else {
                                R.string.thread_input_hint_idle
                            },
                        ),
                    maxLines = state.inputMaxLines,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = LocalSpacing.current.sm)
                    .focusRequester(focusRequester),
            trailing = {
                if (state.running) {
                    StopButton(onClick = { onAction(ChatThreadAction.Stop) })
                }
                SendButton(enabled = enabled, running = state.running, onAction = onAction)
            },
        )
    }
}

/** 发送按钮（docs/tech/08 §4.2：selected 实心；禁用时降透明、点击空转）。 */
@Composable
private fun SendButton(
    enabled: Boolean,
    running: Boolean,
    onAction: (ChatThreadAction) -> Unit,
) {
    AutopIconButton(
        icon = AutopIcons.Send,
        contentDescription =
            stringResource(
                if (running) R.string.thread_send_steer else R.string.thread_send,
            ),
        onClick = { if (enabled) onAction(ChatThreadAction.Send) },
        modifier =
            if (enabled) {
                Modifier
            } else {
                Modifier.alpha(AutopAlpha.DISABLED_CONTENT)
            },
        selected = true,
    )
}

/**
 * 停止按钮（运行中显示）：与发送同为 [AutopIconButton]（44dp 统一尺寸），
 * 图标 error 色（IconButtons 文档：自定义色包 [LocalContentColor]，N172）。
 */
@Composable
private fun StopButton(onClick: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
        AutopIconButton(
            icon = AutopIcons.Stop,
            contentDescription = stringResource(R.string.thread_stop),
            onClick = onClick,
        )
    }
}
