/**
 * 输入区（docs/tech/08 §4.2 InputArea）：附图入口 + 输入栏。
 * 尾部只有一个按钮（与 AI 创建流程页一致的无底色图标按钮）：空闲或输入了内容时是发送，
 * 运行中且输入为空时换成停止图标；运行中输入了补充指令则回到发送（steer），不会两个按钮并排。
 * `ui.chat.backgroundOnSend` 的让屏行为由 ViewModel 经 navEvents 交给 app。
 */
package com.example.autop.feature.chat.thread

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.chat.R
import com.example.autop.task.ImageAttachment

/** 发送与停止图标互换的淡入淡出时长（毫秒）。 */
private const val SWAP_MS: Int = 150

/** 尾部按钮当前是发送还是停止。 */
private enum class TrailingMode { SEND, STOP }

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
        PendingImages(images = state.images, onAction = onAction)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutopModelSelector(
                selectedId = state.modelProfileId,
                profiles = state.modelOptions,
                enabled = !state.running,
                onSelect = { onAction(ChatThreadAction.SetModel(it)) },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!state.running && state.images.size < state.maxImages) AddImageButton(onAction)
        }
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
            trailing = { TrailingButton(state = state, enabled = enabled, onAction = onAction) },
        )
    }
}

/** 系统相册选图（Photo Picker 不需要存储权限）；选中的 Uri 交给 ViewModel 读取压缩。 */
@Composable
private fun AddImageButton(onAction: (ChatThreadAction) -> Unit) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) onAction(ChatThreadAction.AddImages(uris))
        }
    AutopIconButton(
        icon = AutopIcons.AddPhoto,
        contentDescription = stringResource(R.string.thread_add_image),
        onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
}

/** 待发送图片缩略图行：每张右上角带移除按钮。 */
@Composable
private fun PendingImages(
    images: List<ImageAttachment>,
    onAction: (ChatThreadAction) -> Unit,
) {
    if (images.isEmpty()) return
    val spacing = LocalSpacing.current
    val description = stringResource(R.string.thread_image_description)
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = spacing.sm),
    ) {
        images.forEachIndexed { index, image ->
            Box {
                Thumbnail(
                    key = image,
                    size = AutopDimens.attachThumb,
                    contentDescription = description,
                    decode = { px -> decodeSampledBytes(image.bytes, px) },
                )
                Icon(
                    imageVector = AutopIcons.Close,
                    contentDescription = stringResource(R.string.thread_remove_image),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(spacing.xs)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = AutopAlpha.OVERLAY_SCRIM),
                                CircleShape,
                            ).clickable { onAction(ChatThreadAction.RemoveImage(index)) }
                            .padding(spacing.xs)
                            .size(IconSize.Sm.scaled()),
                )
            }
        }
    }
}

/** 尾部唯一的按钮：发送与停止互换，两者同为 44dp 图标按钮，切换时不会跳动布局。 */
@Composable
private fun TrailingButton(
    state: ChatThreadUiState,
    enabled: Boolean,
    onAction: (ChatThreadAction) -> Unit,
) {
    val mode = if (state.running && state.input.isBlank()) TrailingMode.STOP else TrailingMode.SEND
    val duration = if (LocalMotionEnabled.current) SWAP_MS else 0
    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
        label = "chat_input_trailing",
    ) { target ->
        when (target) {
            TrailingMode.STOP -> StopButton(onClick = { onAction(ChatThreadAction.Stop) })
            TrailingMode.SEND ->
                SendButton(
                    sendable = enabled && (state.input.isNotBlank() || state.images.isNotEmpty()),
                    running = state.running,
                    onClick = { if (enabled) onAction(ChatThreadAction.Send) },
                )
        }
    }
}

/** 发送按钮：无底色图标；输入为空或无障碍未连接时降透明（点击仍交给 ViewModel 校验）。 */
@Composable
private fun SendButton(
    sendable: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    AutopIconButton(
        icon = AutopIcons.Send,
        contentDescription =
            stringResource(
                if (running) R.string.thread_send_steer else R.string.thread_send,
            ),
        onClick = onClick,
        modifier = if (sendable) Modifier else Modifier.alpha(AutopAlpha.DISABLED_CONTENT),
    )
}

/** 停止按钮（运行中、输入为空时取代发送）：与 AI 创建页一致的无底色停止图标。 */
@Composable
private fun StopButton(onClick: () -> Unit) {
    AutopIconButton(
        icon = AutopIcons.Stop,
        contentDescription = stringResource(R.string.thread_stop),
        onClick = onClick,
    )
}
