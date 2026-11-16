/**
 * 运行横幅与提问卡预览（docs/tech/08 §7，ADR-0039）：亮色、暗色各一例。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.thread.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewTheme

private const val PREVIEW_BLOCKED_DETAIL: String = "打开支付页前需要你确认"
private const val PREVIEW_QUESTION: String = "下午 2 点的车次要一等座还是二等座？"

@Preview(name = "banner light")
@Composable
internal fun RunBannerLightPreview() =
    PreviewTheme {
        RunBannerCard(kind = BannerKind.BLOCKED, detail = PREVIEW_BLOCKED_DETAIL, onAction = {})
    }

@Preview(name = "banner dark")
@Composable
internal fun RunBannerDarkPreview() =
    PreviewTheme(dark = true) {
        RunBannerCard(kind = BannerKind.PAUSED, detail = null, onAction = {}, pending = TimelineAction.Resume)
    }

@Preview(name = "ask light")
@Composable
internal fun AskQuestionLightPreview() =
    PreviewTheme {
        AskQuestionCard(question = PREVIEW_QUESTION, options = listOf("二等座", "一等座"), onAnswer = {})
    }

@Preview(name = "ask dark")
@Composable
internal fun AskQuestionDarkPreview() =
    PreviewTheme(dark = true) {
        AskQuestionCard(question = PREVIEW_QUESTION, options = listOf("二等座", "一等座"), onAnswer = {})
    }
