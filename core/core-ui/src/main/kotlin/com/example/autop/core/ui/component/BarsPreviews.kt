/**
 * 输入栏与顶栏组件预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme

/** 输入栏多行样本行数。 */
private const val SAMPLE_MAX_LINES: Int = 2

@Preview(name = "light")
@Composable
internal fun AutopInputBarPreview() =
    PreviewTheme {
        AutopInputBar(
            value = "",
            onValueChange = {},
            spec = InputBarSpec(placeholder = PreviewData.INPUT, maxLines = SAMPLE_MAX_LINES),
        )
    }

@Preview(name = "dark")
@Composable
internal fun AutopInputBarDarkPreview() =
    PreviewTheme(dark = true) {
        AutopInputBar(value = PreviewData.BODY, onValueChange = {})
    }

@Preview(name = "light")
@Composable
internal fun SubPageTopBarPreview() =
    PreviewTheme {
        SubPageTopBar(title = PreviewData.TITLE, onBack = {})
    }

@Preview(name = "dark")
@Composable
internal fun SubPageTopBarDarkPreview() =
    PreviewTheme(dark = true) {
        SubPageTopBar(title = PreviewData.TITLE, onBack = {}) {
            AutopIconButton(
                icon = AutopIcons.Refresh,
                contentDescription = PreviewData.BUTTON,
                onClick = {},
            )
        }
    }

@Preview(name = "light")
@Composable
internal fun TopLevelHeaderPreview() =
    PreviewTheme {
        TopLevelHeader(title = PreviewData.TITLE)
    }

@Preview(name = "dark")
@Composable
internal fun TopLevelHeaderDarkPreview() =
    PreviewTheme(dark = true) {
        TopLevelHeader(title = PreviewData.TITLE) {
            AutopIconButton(
                icon = AutopIcons.Search,
                contentDescription = PreviewData.BUTTON,
                onClick = {},
            )
        }
    }
