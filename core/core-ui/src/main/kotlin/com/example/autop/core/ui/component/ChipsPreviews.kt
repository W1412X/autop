/**
 * Chip 与图标按钮组件预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.core.ui.theme.StatusTone

@Preview(name = "light")
@Composable
internal fun AutopChipPreview() =
    PreviewTheme {
        AutopChip(text = PreviewData.CHIP, selected = true, onClick = {}, icon = AutopIcons.Search)
    }

@Preview(name = "dark")
@Composable
internal fun AutopChipDarkPreview() =
    PreviewTheme(dark = true) {
        AutopChip(text = PreviewData.CHIP, selected = false, onClick = {})
    }

@Preview(name = "light")
@Composable
internal fun StatusChipPreview() =
    PreviewTheme {
        StatusChip(text = PreviewData.STATUS, tone = StatusTone.RUNNING)
    }

@Preview(name = "dark")
@Composable
internal fun StatusChipDarkPreview() =
    PreviewTheme(dark = true) {
        StatusChip(text = PreviewData.STATUS, tone = StatusTone.BLOCKED)
    }

@Preview(name = "light")
@Composable
internal fun AutopIconButtonPreview() =
    PreviewTheme {
        AutopIconButton(
            icon = AutopIcons.Settings,
            contentDescription = PreviewData.BUTTON,
            onClick = {},
        )
    }

@Preview(name = "dark")
@Composable
internal fun AutopIconButtonDarkPreview() =
    PreviewTheme(dark = true) {
        AutopIconButton(
            icon = AutopIcons.Settings,
            contentDescription = PreviewData.BUTTON,
            onClick = {},
            selected = true,
        )
    }

@Preview(name = "light")
@Composable
internal fun IconActionPreview() =
    PreviewTheme {
        IconAction(label = PreviewData.BUTTON, icon = AutopIcons.Mic, onClick = {})
    }

@Preview(name = "dark")
@Composable
internal fun IconActionDarkPreview() =
    PreviewTheme(dark = true) {
        IconAction(label = PreviewData.BUTTON, icon = AutopIcons.Mic, onClick = {}, selected = true)
    }
