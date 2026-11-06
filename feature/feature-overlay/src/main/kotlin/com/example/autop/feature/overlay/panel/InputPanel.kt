/**
 * 输入面板（docs/tech/08 §5.2）：`AutopInputBar` 单行起最多 3 行 + 发送 +
 * 「针对当前屏幕」chip（默认 `ui.overlay.attachScreenDefault`）。语音输入暂不提供，入口已隐藏。
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
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R

/** 输入面板最多行数（docs/tech/08 §5.2 单行起最多 3 行）。 */
private const val INPUT_MAX_LINES: Int = 3

/** 输入面板：发一条新任务（`threadId = null` 新建对话）。 */
@Composable
internal fun InputPanel(
    callbacks: PanelCallbacks,
    attachScreenDefault: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var attachScreen by remember { mutableStateOf(attachScreenDefault) }
    val spacing = LocalSpacing.current
    AutopInputBar(
        value = text,
        onValueChange = { text = it },
        spec =
            InputBarSpec(
                placeholder = stringResource(R.string.panel_input_placeholder),
                maxLines = INPUT_MAX_LINES,
            ),
        modifier = modifier.fillMaxWidth(),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        AutopChip(
            text = stringResource(R.string.panel_attach_screen),
            selected = attachScreen,
            onClick = { attachScreen = !attachScreen },
        )
        Spacer(modifier = Modifier.weight(1f))
        AutopIconButton(
            icon = Icons.Outlined.Send,
            contentDescription = stringResource(R.string.panel_send),
            onClick = {
                if (text.isNotBlank()) {
                    callbacks.onSend(text.trim(), attachScreen)
                    text = ""
                }
            },
        )
    }
}
