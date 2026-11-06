/**
 * 面板预览（docs/tech/08 §7）：输入面板亮/暗各一例。
 * 运行/问题/拦截内容迁入任务进度悬浮窗（N182），预览见 progress 包。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.feature.overlay.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "input light")
@Composable
internal fun InputPanelPreview() =
    PreviewTheme {
        PanelScreen(
            callbacks = PreviewCallbacks,
            attachScreenDefault = true,
        )
    }

@Preview(name = "input dark")
@Composable
internal fun InputPanelDarkPreview() =
    PreviewTheme(dark = true) {
        PanelScreen(
            callbacks = PreviewCallbacks,
            attachScreenDefault = false,
        )
    }

private val PreviewCallbacks =
    PanelCallbacks(
        onSend = { _, _ -> },
        onDismiss = {},
    )
