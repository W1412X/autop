/**
 * 对话输入区（docs/tech/08 §4.2 InputArea）：基于共用的 [AutopComposer]。
 * 顶部：模型选择 + 「只聊天」开关；输入框左侧「＋」附件菜单（图片、拍照）；
 * 右侧：没有输入时是语音（系统语音识别），有输入或图片时是发送，运行中是停止。
 * `ui.chat.backgroundOnSend` 的让屏行为由 ViewModel 经 navEvents 交给 app。
 */
package com.example.autop.feature.chat.thread

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.autop.core.thread.ui.Thumbnail
import com.example.autop.core.thread.ui.decodeSampledBytes
import com.example.autop.core.ui.component.AutopComposer
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.ComposerTrailing
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.VoiceErrorBanner
import com.example.autop.core.ui.component.rememberVoiceStarter
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.chat.R
import com.example.autop.task.ImageAttachment
import java.io.File

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
        PendingImages(images = state.images, onAction = onAction)
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
                    { AttachMenuButton(onAction) }
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

/** 「＋」附件菜单：图片（系统相册，不需要存储权限）与拍照（系统相机）。 */
@Composable
private fun AttachMenuButton(onAction: (ChatThreadAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) onAction(ChatThreadAction.AddImages(uris))
        }
    // 用户取消拍照不算错误（不提示）；拍成功才加入图片。
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingCapture
            pendingCapture = null
            if (saved && uri != null) onAction(ChatThreadAction.AddImages(listOf(uri)))
        }
    Box {
        AutopIconButton(
            icon = AutopIcons.Add,
            contentDescription = stringResource(R.string.thread_attach),
            compact = true,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.thread_add_image)) },
                leadingIcon = { Icon(imageVector = AutopIcons.AddPhoto, contentDescription = null) },
                onClick = {
                    open = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.thread_camera)) },
                leadingIcon = { Icon(imageVector = AutopIcons.PhotoCamera, contentDescription = null) },
                onClick = {
                    open = false
                    val uri = newCaptureUri(context)
                    pendingCapture = uri
                    try {
                        camera.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        pendingCapture = null
                        onAction(ChatThreadAction.ShowNotice(ThreadNotice.CAMERA_FAILED))
                    }
                },
            )
        }
    }
}

/** 拍照输出文件：放在 cache/camera 下，经 FileProvider 交给系统相机写入。 */
private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
