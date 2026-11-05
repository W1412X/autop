/**
 * 检索向量页预览（docs/tech/08 §7：每个页面亮色、暗色各一个）。
 */
package com.example.autop.feature.settings.me.embedding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.config.LlmEmbeddingSection
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.llm.ModelProfile

@Preview(name = "Embedding light")
@Composable
internal fun EmbeddingScreenLightPreview() {
    PreviewTheme {
        EmbeddingScreen(
            state =
                EmbeddingUiState(
                    section =
                        LlmEmbeddingSection(
                            provider = "openai",
                            profileId = "p1",
                            model = "text-embedding-3-small",
                        ),
                    profiles = listOf(previewProfile()),
                    reindex = ReindexUiState(memory = ReindexProgress(done = 3, total = 10)),
                ),
            onBack = {},
            onAction = {},
        )
    }
}

@Preview(name = "Embedding dark")
@Composable
internal fun EmbeddingScreenDarkPreview() {
    PreviewTheme(dark = true) {
        EmbeddingScreen(state = EmbeddingUiState(), onBack = {}, onAction = {})
    }
}

private fun previewProfile(): ModelProfile =
    ModelProfile(
        id = "p1",
        displayName = "主力模型",
        protocol = "openai",
        baseUrl = "https://api.example.com/v1",
        apiKeyRef = "secret:model:p1",
        model = "gpt-4o-mini",
        contextWindow = 128000,
        maxOutputTokens = 4096,
    )
