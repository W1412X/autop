/**
 * 对话输入区（docs/tech/08 §4.2 InputArea）：基于共用的 [AutopComposer]。
 * 顶部：模型选择 + 「只聊天」开关；输入框左侧「＋」附件菜单（图片、拍照）；
 * 右侧：没有输入时是语音（系统语音识别），有输入或图片时是发送，运行中是停止。
 * `ui.chat.backgroundOnSend` 的让屏行为由 ViewModel 经 navEvents 交给 app。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.thread.ui.AttachImageButton
import com.example.autop.core.thread.ui.PendingImageStrip
import com.example.autop.core.ui.component.AutopComposer
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.ComposerTrailing
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.VoiceErrorBanner
import com.example.autop.core.ui.component.rememberVoiceStarter
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

/** 只聊天开关：布局高度 24dp，Material 开关按 0.7 缩放后的视觉尺寸。 */
private val SWITCH_BOX_HEIGHT = 24.dp
private const val SWITCH_SCALE = 0.7f

/**
 * 对话输入区。
 *
 * @param state 页面状态（输入文本、运行中、只聊天、语音、无障碍连接）。
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
    val startVoice =
        rememberVoiceStarter(
            onGranted = { onAction(ChatThreadAction.StartVoice) },
            onDenied = { onAction(ChatThreadAction.VoiceFailed("speech.permission")) },
        )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge),
    ) {
        PendingImageStrip(images = state.images, onRemove = { onAction(ChatThreadAction.RemoveImage(it)) })
        state.voiceError?.let { VoiceErrorBanner(code = it) }
        AutopComposer(
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
            top = {
                AutopModelSelector(
                    selectedId = state.modelProfileId,
                    profiles = state.modelOptions,
                    enabled = !state.running,
                    onSelect = { onAction(ChatThreadAction.SetModel(it)) },
                    defaultProfileId = state.defaultModelId,
                )
                Spacer(modifier = Modifier.weight(1f))
                // ADR-0035「只聊天」：开启后本轮发送不提供屏幕工具，不占用手机；运行中不切换（下一轮生效）。
                ChatOnlySwitch(chatOnly = state.chatOnly, running = state.running, onAction = onAction)
            },
            leading =
                if (!state.running && state.images.size < state.maxImages) {
                    { ChatAttachButton(onAction) }
                } else {
                    null
                },
            trailing = composerTrailingOf(state),
            sendEnabled = enabled && (state.input.isNotBlank() || state.images.isNotEmpty()),
            listening = state.listening,
            onSend = { if (enabled) onAction(ChatThreadAction.Send) },
            onStop = { onAction(ChatThreadAction.Stop) },
            onVoice = { if (state.listening) onAction(ChatThreadAction.StopVoice) else startVoice() },
            focusRequester = focusRequester,
        )
    }
}

/** 尾部按钮：听写中为语音（停止态）；运行中无输入为停止；无输入且可语音为语音；其余为发送。 */
private fun composerTrailingOf(state: ChatThreadUiState): ComposerTrailing =
    when {
        state.listening -> ComposerTrailing.VOICE
        state.running && state.input.isBlank() -> ComposerTrailing.STOP
        state.voiceAvailable && state.input.isBlank() && state.images.isEmpty() -> ComposerTrailing.VOICE
        else -> ComposerTrailing.SEND
    }

/** 只聊天开关（ADR-0035）：文字与开关同排，布局高度 [SWITCH_BOX_HEIGHT]；运行中不可切换（下一轮生效）。 */
@Composable
private fun ChatOnlySwitch(
    chatOnly: Boolean,
    running: Boolean,
    onAction: (ChatThreadAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.thread_chat_only),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier.padding(start = LocalSpacing.current.xs).height(SWITCH_BOX_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Switch(
                checked = chatOnly,
                onCheckedChange = { onAction(ChatThreadAction.SetChatOnly(it)) },
                enabled = !running,
                modifier = Modifier.graphicsLayer(scaleX = SWITCH_SCALE, scaleY = SWITCH_SCALE),
            )
        }
    }
}

/** 对话的附图按钮：读图失败、拍照失败时通过对话的通知条提示。 */
@Composable
private fun ChatAttachButton(onAction: (ChatThreadAction) -> Unit) {
    AttachImageButton(
        onImages = { onAction(ChatThreadAction.AddImages(it)) },
        onCaptureFailed = { onAction(ChatThreadAction.ShowNotice(ThreadNotice.CAMERA_FAILED)) },
    )
}
