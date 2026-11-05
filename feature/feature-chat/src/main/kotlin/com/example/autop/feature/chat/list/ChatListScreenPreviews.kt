/**
 * 对话列表页预览（docs/tech/08 §7：每个页面亮色、暗色各一个）。
 */
package com.example.autop.feature.chat.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.agent.RunOutcome
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "Chat list light")
@Composable
internal fun ChatListScreenLightPreview() {
    PreviewTheme {
        ChatListScreen(state = previewListState(), onAction = {})
    }
}

@Preview(name = "Chat list dark")
@Composable
internal fun ChatListScreenDarkPreview() {
    PreviewTheme(dark = true) {
        ChatListScreen(state = previewListState(), onAction = {})
    }
}

private fun previewListState(): ChatListUiState {
    val now = System.currentTimeMillis()
    return ChatListUiState(
        threads =
            listOf(
                ChatThreadRow(
                    threadId = "t1",
                    title = "订去上海的高铁票",
                    preview = "已找到下午的 3 个车次",
                    status = RunOutcome.SUCCEEDED,
                    updatedAt = now - RECENT_MS,
                ),
                ChatThreadRow(
                    threadId = "t2",
                    title = "整理相册",
                    preview = "正在按拍摄时间归类…",
                    status = null,
                    updatedAt = now - EARLIER_MS,
                ),
                ChatThreadRow(
                    threadId = "t3",
                    title = null,
                    preview = "策略拦截了这次操作",
                    status = RunOutcome.FAILED,
                    updatedAt = now - OLD_MS,
                ),
            ),
        active =
            ActiveRunCardModel(
                runId = "r1",
                title = "清理微信缓存",
                currentStep = "tap「存储」",
                stepCount = 4,
                budget = 20,
            ),
    )
}

private const val RECENT_MS: Long = 180_000
private const val EARLIER_MS: Long = 7_200_000
private const val OLD_MS: Long = 259_200_000
