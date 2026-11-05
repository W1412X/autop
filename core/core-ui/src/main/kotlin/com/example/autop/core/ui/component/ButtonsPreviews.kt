/**
 * 按钮组件预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "light")
@Composable
internal fun AutopButtonPreview() =
    PreviewTheme {
        AutopButton(text = PreviewData.BUTTON, onClick = {}, icon = AutopIcons.Add)
    }

@Preview(name = "dark")
@Composable
internal fun AutopButtonDarkPreview() =
    PreviewTheme(dark = true) {
        AutopButton(text = PreviewData.BUTTON, onClick = {}, icon = AutopIcons.Add)
    }

@Preview(name = "light")
@Composable
internal fun AutopTonalButtonPreview() =
    PreviewTheme {
        AutopTonalButton(text = PreviewData.BUTTON, onClick = {})
    }

@Preview(name = "dark")
@Composable
internal fun AutopTonalButtonDarkPreview() =
    PreviewTheme(dark = true) {
        AutopTonalButton(text = PreviewData.BUTTON, onClick = {})
    }

@Preview(name = "light")
@Composable
internal fun AutopOutlinedButtonPreview() =
    PreviewTheme {
        AutopOutlinedButton(text = PreviewData.BUTTON, onClick = {})
    }

@Preview(name = "dark")
@Composable
internal fun AutopOutlinedButtonDarkPreview() =
    PreviewTheme(dark = true) {
        AutopOutlinedButton(text = PreviewData.BUTTON, onClick = {})
    }

@Preview(name = "light")
@Composable
internal fun AutopTextButtonPreview() =
    PreviewTheme {
        AutopTextButton(text = PreviewData.BUTTON, onClick = {})
    }

@Preview(name = "dark")
@Composable
internal fun AutopTextButtonDarkPreview() =
    PreviewTheme(dark = true) {
        AutopTextButton(text = PreviewData.BUTTON, onClick = {}, destructive = true)
    }
