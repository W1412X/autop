/**
 * 模型配置页预览（docs/tech/08 §7：每个页面亮色、暗色各一个）。
 */
package com.example.autop.feature.settings.me.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelRoles
import com.example.autop.llm.ProbeReport
import com.example.autop.llm.ProbeResult
import com.example.autop.llm.ProbeVerdict

@Preview(name = "Models list light")
@Composable
internal fun ModelsScreenLightPreview() {
    PreviewTheme {
        ModelsScreen(state = previewListState(), onBack = {}, onAction = {})
    }
}

@Preview(name = "Models list dark")
@Composable
internal fun ModelsScreenDarkPreview() {
    PreviewTheme(dark = true) {
        ModelsScreen(state = previewListState(), onBack = {}, onAction = {})
    }
}

@Preview(name = "Model edit light")
@Composable
internal fun ModelEditScreenLightPreview() {
    PreviewTheme {
        ModelEditScreen(state = previewEditState(), draft = previewDraft(), onBack = {}, onAction = {})
    }
}

@Preview(name = "Model edit dark")
@Composable
internal fun ModelEditScreenDarkPreview() {
    PreviewTheme(dark = true) {
        ModelEditScreen(state = previewEditState(), draft = previewDraft(), onBack = {}, onAction = {})
    }
}

private fun previewListState(): ModelsUiState =
    ModelsUiState(
        profiles = listOf(previewProfile("p1", "主力模型"), previewProfile("p2", "轻量模型")),
        roles = ModelRoles(main = "p1", vision = "p1", light = "p2"),
    )

private fun previewEditState(): ModelsUiState =
    ModelsUiState(
        profiles = listOf(previewProfile("p1", "主力模型")),
        roles = ModelRoles(main = "p1", vision = "p1", light = null),
        probe =
            ProbeUiState(
                running = false,
                report =
                    ProbeReport(
                        text = ProbeResult(verdict = ProbeVerdict.SUPPORTED, elapsedMs = 1200),
                        tools = ProbeResult(verdict = ProbeVerdict.SUPPORTED, elapsedMs = 800),
                        vision = ProbeResult(verdict = ProbeVerdict.UNSUPPORTED, elapsedMs = 300),
                    ),
            ),
    )

private fun previewDraft(): ModelDraft =
    ModelDraft(
        id = "p1",
        isNew = false,
        protocol = "openai",
        displayName = "主力模型",
        baseUrl = "https://api.example.com/v1",
        apiKeyTail = "1234",
        model = "gpt-4o-mini",
        contextWindow = "128000",
        maxOutputTokens = "4096",
    )

private fun previewProfile(
    id: String,
    name: String,
): ModelProfile =
    ModelProfile(
        id = id,
        displayName = name,
        protocol = "openai",
        baseUrl = "https://api.example.com/v1",
        apiKeyRef = "secret:model:$id",
        model = "gpt-4o-mini",
        contextWindow = 128000,
        maxOutputTokens = 4096,
    )
