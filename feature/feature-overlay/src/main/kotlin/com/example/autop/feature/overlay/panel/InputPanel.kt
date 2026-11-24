/**
 * 输入面板（docs/tech/08 §5.2）：`AutopInputBar` 单行起最多 3 行 + 发送。语音输入暂不提供，入口已隐藏。
 */
package com.example.autop.feature.overlay.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.common.BackgroundRunBlock
import com.example.autop.core.ui.R
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopModelChip
import com.example.autop.core.ui.component.AutopModelPicker
import com.example.autop.core.ui.component.BackgroundRunRow
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.rememberAutopModelPickerState
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R as OverlayR

private fun BackgroundRunBlock?.messageRes(): Int? =
    when (this) {
        BackgroundRunBlock.RUNNING -> R.string.core_ui_background_run_locked
        BackgroundRunBlock.UNSUPPORTED -> R.string.core_ui_background_run_unsupported
        BackgroundRunBlock.CHANNEL_DISCONNECTED -> R.string.core_ui_background_run_disconnected
        null -> null
    }

/** 输入面板最多行数（docs/tech/08 §5.2 单行起最多 3 行）。 */
private const val INPUT_MAX_LINES: Int = 3

/** 输入面板：发一条新任务（`threadId = null` 新建对话）。 */
@Composable
internal fun InputPanel(
    callbacks: PanelCallbacks,
    modelOptions: List<OptionItem> = emptyList(),
    backgroundRunBlock: BackgroundRunBlock? = null,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var modelProfileId by remember { mutableStateOf<String?>(null) }
    var backgroundScreen by remember { mutableStateOf(false) }
    val picker = rememberAutopModelPickerState()
    val spacing = LocalSpacing.current
    val effectiveBlock =
        backgroundRunBlock.takeUnless { backgroundScreen && it == BackgroundRunBlock.CHANNEL_DISCONNECTED }
    AutopInputBar(
        value = text,
        onValueChange = { text = it },
        spec =
            InputBarSpec(
                placeholder = stringResource(OverlayR.string.panel_input_placeholder),
                maxLines = INPUT_MAX_LINES,
            ),
        modifier = modifier.fillMaxWidth(),
    )
    BackgroundRunRow(
        checked = backgroundScreen,
        onCheckedChange = { backgroundScreen = it },
        enabled = effectiveBlock == null,
        unavailableReason = effectiveBlock.messageRes()?.let { stringResource(it) },
        modifier = modifier,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        AutopModelChip(
            state = picker,
            selectedId = modelProfileId,
            profiles = modelOptions,
            enabled = true,
        )
        Spacer(modifier = Modifier.weight(1f))
        AutopIconButton(
            icon = Icons.Outlined.Send,
            contentDescription = stringResource(OverlayR.string.panel_send),
            onClick = {
                if (text.isNotBlank()) {
                    callbacks.onSend(text.trim(), modelProfileId, backgroundScreen)
                    text = ""
                }
            },
        )
    }
    // 悬浮窗里不能开弹层（Dialog 需要 Activity 窗口 token，否则闪退），列表就地展开，面板窗口高度随内容增长。
    AutopModelPicker(
        state = picker,
        selectedId = modelProfileId,
        profiles = modelOptions,
        onSelect = { modelProfileId = it },
        inline = true,
    )
}
