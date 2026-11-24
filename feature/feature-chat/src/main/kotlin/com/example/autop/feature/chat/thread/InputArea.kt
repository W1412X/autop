/**
 * 对话输入区（docs/tech/08 §4.2 InputArea）：基于共用的 [AutopComposer]。
 * 顶部：模型选择 + 「只聊天」开关；输入框左侧「＋」附件菜单（图片、拍照）；
 * 右侧：没有输入时是语音（系统语音识别），有输入或图片时是发送，运行中是停止。
 * `ui.chat.backgroundOnSend` 的让屏行为由 ViewModel 经 navEvents 交给 app。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.example.autop.core.common.BackgroundRunBlock
import com.example.autop.core.common.RunMode
import com.example.autop.core.thread.ui.AttachImageButton
import com.example.autop.core.thread.ui.PendingImageStrip
import com.example.autop.core.ui.component.AutopComposer
import com.example.autop.core.ui.component.AutopFlatChip
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.BackgroundRunRow
import com.example.autop.core.ui.component.ComposerTrailing
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.VoiceErrorToast
import com.example.autop.core.ui.component.rememberVoiceStarter
import com.example.autop.core.ui.component.vector
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R
import com.example.autop.core.ui.R as CoreUiR

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
        BackgroundRunToggle(state, onAction)
        VoiceErrorToast(code = state.voiceError)
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
                // ADR-0040：运行模式三档；完全模式受总闸与无线注入约束，运行中不切换（下一轮生效）。
                RunModeSelector(runMode = state.runMode, running = state.running, onAction = onAction)
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
            voiceLevel = state.voiceLevel,
            onSend = { if (enabled) onAction(ChatThreadAction.Send) },
            onStop = { onAction(ChatThreadAction.Stop) },
            onVoice = { if (state.listening) onAction(ChatThreadAction.StopVoice) else startVoice() },
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun BackgroundRunToggle(
    state: ChatThreadUiState,
    onAction: (ChatThreadAction) -> Unit,
) {
    BackgroundRunRow(
        checked = state.backgroundScreen,
        onCheckedChange = { onAction(ChatThreadAction.SetBackgroundScreen(it)) },
        enabled = state.backgroundRunBlock == null,
        unavailableReason = state.backgroundRunBlock?.let { stringResource(it.messageRes()) },
    )
}

/** 尾部按钮：听写中为语音（停止态）；运行中无输入为停止；无输入且可语音为语音；其余为发送。 */
private fun composerTrailingOf(state: ChatThreadUiState): ComposerTrailing =
    when {
        state.listening -> ComposerTrailing.VOICE
        state.running && state.input.isBlank() -> ComposerTrailing.STOP
        state.voiceAvailable && state.input.isBlank() && state.images.isEmpty() -> ComposerTrailing.VOICE
        else -> ComposerTrailing.SEND
    }

/**
 * 运行模式芯片（ADR-0040，方案 A）：与模型芯片同一套交互，芯片前置模式图标，点击弹出带图标与说明的选择面板。
 * 运行中不可切换（下一轮生效）。
 */
@Composable
private fun RunModeSelector(
    runMode: RunMode,
    running: Boolean,
    onAction: (ChatThreadAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    AutopFlatChip(
        text = stringResource(runModeLabelRes(runMode)),
        icon = runModeIcon(runMode).vector(),
        onClick = { if (!running) open = true },
        labelTrailing = {
            Text(
                text = ARROW,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = LocalSpacing.current.xs),
            )
        },
    )
    if (open) {
        OptionSheet(
            title = stringResource(R.string.thread_run_mode_title),
            options = RunMode.entries.map { modeOption(it) },
            selectedId = runMode.id,
            onSelect = { id -> RunMode.fromId(id)?.let { onAction(ChatThreadAction.SetRunMode(it)) } },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun modeOption(mode: RunMode): OptionItem =
    OptionItem(
        id = mode.id,
        label = stringResource(runModeLabelRes(mode)),
        description = stringResource(runModeDescRes(mode)),
        icon = runModeIcon(mode),
    )

/** 运行模式档位的图标（ADR-0040）。 */
private fun runModeIcon(mode: RunMode): OptionIcon =
    when (mode) {
        RunMode.CHAT_ONLY -> OptionIcon.MODE_CHAT_ONLY
        RunMode.STANDARD -> OptionIcon.MODE_STANDARD
        RunMode.FULL -> OptionIcon.MODE_FULL
    }

/** 运行模式档位的名称资源（ADR-0040）。 */
private fun runModeLabelRes(mode: RunMode): Int =
    when (mode) {
        RunMode.CHAT_ONLY -> R.string.thread_run_mode_chat_only
        RunMode.STANDARD -> R.string.thread_run_mode_standard
        RunMode.FULL -> R.string.thread_run_mode_full
    }

/** 运行模式档位的说明资源（ADR-0040）。 */
private fun runModeDescRes(mode: RunMode): Int =
    when (mode) {
        RunMode.CHAT_ONLY -> R.string.thread_run_mode_chat_only_desc
        RunMode.STANDARD -> R.string.thread_run_mode_standard_desc
        RunMode.FULL -> R.string.thread_run_mode_full_desc
    }

private fun BackgroundRunBlock.messageRes(): Int =
    when (this) {
        BackgroundRunBlock.RUNNING -> CoreUiR.string.core_ui_background_run_locked
        BackgroundRunBlock.UNSUPPORTED -> CoreUiR.string.core_ui_background_run_unsupported
        BackgroundRunBlock.CHANNEL_DISCONNECTED -> CoreUiR.string.core_ui_background_run_disconnected
    }

private const val ARROW = "▾"

/** 对话的附图按钮：读图失败、拍照失败时通过对话的通知条提示。 */
@Composable
private fun ChatAttachButton(onAction: (ChatThreadAction) -> Unit) {
    AttachImageButton(
        onImages = { onAction(ChatThreadAction.AddImages(it)) },
        onCaptureFailed = { onAction(ChatThreadAction.ShowNotice(ThreadNotice.CAMERA_FAILED)) },
    )
}
