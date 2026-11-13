/**
 * 对话页预览（docs/tech/08 §7：每个页面亮色、暗色各一个）。
 * 两组状态分别覆盖运行中（流式文本 / 运行中步骤 / 计划卡 / 分叉标记）
 * 与等待回答（结果卡 / 失败步骤 / 暂停横幅 / AskCard）的时间线样式；
 * 时间线假数据见 [previewRunningEntries] / [previewAskEntries]。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "Thread running light")
@Composable
internal fun ChatThreadRunningLightPreview() {
    PreviewTheme {
        ChatThreadScreen(state = previewRunningState(), onAction = {}, onBack = {})
    }
}

@Preview(name = "Thread running dark")
@Composable
internal fun ChatThreadRunningDarkPreview() {
    PreviewTheme(dark = true) {
        ChatThreadScreen(state = previewRunningState(), onAction = {}, onBack = {})
    }
}

@Preview(name = "Thread ask light")
@Composable
internal fun ChatThreadAskLightPreview() {
    PreviewTheme {
        ChatThreadScreen(state = previewAskState(), onAction = {}, onBack = {})
    }
}

@Preview(name = "Thread ask dark")
@Composable
internal fun ChatThreadAskDarkPreview() {
    PreviewTheme(dark = true) {
        ChatThreadScreen(state = previewAskState(), onAction = {}, onBack = {})
    }
}

private fun previewRunningState(): ChatThreadUiState =
    ChatThreadUiState(
        threadId = "t1",
        title = "订去上海的高铁票",
        entries = previewRunningEntries(),
        running = true,
        modelOptions = PREVIEW_MODELS,
        defaultModelId = PREVIEW_MODELS.first().id,
    )

private fun previewAskState(): ChatThreadUiState =
    ChatThreadUiState(
        threadId = "t1",
        title = "订去上海的高铁票",
        entries = previewAskEntries(),
        running = false,
        modelOptions = PREVIEW_MODELS,
        defaultModelId = PREVIEW_MODELS.first().id,
        waitingQuestion =
            WaitingQuestion(
                question = "下午 2 点的车次要一等座还是二等座？",
                options = listOf("二等座", "一等座", "商务座"),
            ),
    )

private val PREVIEW_MODELS: List<OptionItem> =
    listOf(
        OptionItem(id = "m1", label = "GPT-4o"),
        OptionItem(id = "m2", label = "Claude 4.5 Sonnet"),
    )
