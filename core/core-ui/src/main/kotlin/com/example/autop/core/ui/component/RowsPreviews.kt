/**
 * 设置页行组件预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "light")
@Composable
internal fun SwitchRowPreview() =
    PreviewTheme {
        SwitchRow(
            title = PreviewData.TITLE,
            subtitle = PreviewData.SUBTITLE,
            checked = true,
            onCheckedChange = {},
        )
    }

@Preview(name = "dark")
@Composable
internal fun SwitchRowDarkPreview() =
    PreviewTheme(dark = true) {
        SwitchRow(
            title = PreviewData.TITLE,
            subtitle = null,
            checked = false,
            onCheckedChange = {},
        )
    }

@Preview(name = "light")
@Composable
internal fun OptionRowPreview() =
    PreviewTheme {
        OptionRow(
            title = PreviewData.TITLE,
            subtitle = PreviewData.SUBTITLE,
            value = PreviewData.VALUE,
            onClick = {},
        )
    }

@Preview(name = "dark")
@Composable
internal fun OptionRowDarkPreview() =
    PreviewTheme(dark = true) {
        OptionRow(
            title = PreviewData.TITLE,
            subtitle = null,
            value = PreviewData.VALUE,
            onClick = {},
        )
    }

@Preview(name = "light")
@Composable
internal fun InfoRowPreview() =
    PreviewTheme {
        InfoRow(title = PreviewData.TITLE, subtitle = PreviewData.SUBTITLE, value = PreviewData.VALUE)
    }

@Preview(name = "dark")
@Composable
internal fun InfoRowDarkPreview() =
    PreviewTheme(dark = true) {
        InfoRow(title = PreviewData.TITLE, subtitle = null, value = PreviewData.VALUE)
    }

@Preview(name = "light")
@Composable
internal fun ActionRowPreview() =
    PreviewTheme {
        ActionRow(title = PreviewData.TITLE, subtitle = PreviewData.SUBTITLE, onClick = {})
    }

@Preview(name = "dark")
@Composable
internal fun ActionRowDarkPreview() =
    PreviewTheme(dark = true) {
        ActionRow(title = PreviewData.TITLE, subtitle = null, onClick = {}, destructive = true)
    }

@Preview(name = "light")
@Composable
internal fun NavRowPreview() =
    PreviewTheme {
        NavRow(title = PreviewData.TITLE, subtitle = PreviewData.SUBTITLE, onClick = {})
    }

@Preview(name = "dark")
@Composable
internal fun NavRowDarkPreview() =
    PreviewTheme(dark = true) {
        NavRow(title = PreviewData.TITLE, subtitle = PreviewData.SUBTITLE, onClick = {})
    }
