/**
 * 对话框、弹层与骨架屏预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "light")
@Composable
internal fun ConfirmDialogPreview() =
    PreviewTheme {
        ConfirmDialog(
            title = PreviewData.TITLE,
            text = PreviewData.BODY,
            onConfirm = {},
            onDismiss = {},
        )
    }

@Preview(name = "dark")
@Composable
internal fun ConfirmDialogDarkPreview() =
    PreviewTheme(dark = true) {
        ConfirmDialog(
            title = PreviewData.TITLE,
            text = PreviewData.BODY,
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }

@Preview(name = "light")
@Composable
internal fun InfoDialogPreview() =
    PreviewTheme {
        InfoDialog(
            title = PreviewData.TITLE,
            text = PreviewData.BODY,
            onDismiss = {},
        )
    }

@Preview(name = "dark")
@Composable
internal fun InfoDialogDarkPreview() =
    PreviewTheme(dark = true) {
        InfoDialog(
            title = PreviewData.TITLE,
            text = PreviewData.BODY,
            onDismiss = {},
        )
    }

@Preview(name = "light")
@Composable
internal fun OptionSheetPreview() =
    PreviewTheme {
        OptionSheet(
            title = PreviewData.TITLE,
            options = sampleOptions(),
            selectedId = "option-a",
            onSelect = {},
            onDismiss = {},
        )
    }

@Preview(name = "dark")
@Composable
internal fun OptionSheetDarkPreview() =
    PreviewTheme(dark = true) {
        OptionSheet(
            title = null,
            options = sampleOptions(),
            selectedId = "option-b",
            onSelect = {},
            onDismiss = {},
        )
    }

@Preview(name = "light")
@Composable
internal fun SkeletonRowPreview() =
    PreviewTheme {
        SkeletonRow()
    }

@Preview(name = "dark")
@Composable
internal fun SkeletonRowDarkPreview() =
    PreviewTheme(dark = true) {
        SkeletonRow()
    }

private fun sampleOptions(): List<OptionItem> =
    listOf(
        OptionItem(id = "option-a", label = PreviewData.TITLE, description = PreviewData.SUBTITLE),
        OptionItem(id = "option-b", label = PreviewData.VALUE),
        OptionItem(id = "option-c", label = PreviewData.CHIP, description = PreviewData.SUBTITLE),
    )
