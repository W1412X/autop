/**
 * 列表行与分页列表预览（docs/tech/08 §7）：每个公共组件亮/暗各一例，取 [PreviewData] 样本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.core.ui.theme.StatusTone

/** 进度条样本值。 */
private const val SAMPLE_PROGRESS: Float = 0.4f

@Preview(name = "light")
@Composable
internal fun ListRowPreview() =
    PreviewTheme {
        ListRow(
            title = PreviewData.TITLE,
            subtitle = PreviewData.SUBTITLE_JOINED,
            leading = AutopIcons.Description,
        )
    }

@Preview(name = "dark")
@Composable
internal fun ListRowDarkPreview() =
    PreviewTheme(dark = true) {
        ListRow(
            title = PreviewData.TITLE,
            subtitle = PreviewData.SUBTITLE_JOINED,
            trailing = {
                StatusChip(text = PreviewData.STATUS, tone = StatusTone.SUCCEEDED)
            },
        )
    }

@Preview(name = "light")
@Composable
internal fun ListRowProgressPreview() =
    PreviewTheme {
        ListRow(
            title = PreviewData.TITLE,
            subtitle = PreviewData.SUBTITLE,
            progress = SAMPLE_PROGRESS,
            leading = AutopIcons.Folder,
        )
    }

@Preview(name = "dark")
@Composable
internal fun ListRowProgressDarkPreview() =
    PreviewTheme(dark = true) {
        ListRow(
            title = PreviewData.TITLE,
            subtitle = PreviewData.SUBTITLE,
            progress = SAMPLE_PROGRESS,
            leading = AutopIcons.Folder,
        )
    }

@Preview(name = "light")
@Composable
internal fun PagedListPreview() =
    PreviewTheme {
        PagedList(
            items = listOf(PreviewData.TITLE, PreviewData.SUBTITLE, PreviewData.SUBTITLE_JOINED),
            state = PagedListState(loading = true),
            onLoadMore = {},
        ) { item ->
            ListRow(title = item, subtitle = PreviewData.SUBTITLE, leading = AutopIcons.Description)
        }
    }

@Preview(name = "dark")
@Composable
internal fun PagedListDarkPreview() =
    PreviewTheme(dark = true) {
        PagedList(
            items = listOf(PreviewData.TITLE, PreviewData.SUBTITLE),
            state = PagedListState(error = PreviewData.ERROR),
            onLoadMore = {},
        ) { item ->
            ListRow(title = item, subtitle = PreviewData.SUBTITLE)
        }
    }
