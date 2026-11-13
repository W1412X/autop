/**
 * 状态块预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "light")
@Composable
internal fun EmptyStatePreview() =
    PreviewTheme {
        EmptyState(text = PreviewData.BODY)
    }

@Preview(name = "dark")
@Composable
internal fun EmptyStateDarkPreview() =
    PreviewTheme(dark = true) {
        EmptyState(text = PreviewData.BODY, icon = AutopIcons.Folder)
    }

@Preview(name = "light")
@Composable
internal fun ErrorStatePreview() =
    PreviewTheme {
        ErrorState(text = PreviewData.ERROR, onRetry = {})
    }

@Preview(name = "dark")
@Composable
internal fun ErrorStateDarkPreview() =
    PreviewTheme(dark = true) {
        ErrorState(text = PreviewData.ERROR, onRetry = {})
    }

@Preview(name = "light")
@Composable
internal fun LoadingStatePreview() =
    PreviewTheme {
        LoadingState()
    }

@Preview(name = "dark")
@Composable
internal fun LoadingStateDarkPreview() =
    PreviewTheme(dark = true) {
        LoadingState(text = PreviewData.BODY)
    }
