/**
 * 提示词页状态（docs/tech/08 §4.5"提示词"）。
 */
package com.example.autop.feature.settings.me.prompts

import com.example.autop.core.prompt.PromptSource

/** 一个提示词模板行（含覆盖后的正文）。 */
public data class PromptItem(
    val id: String,
    val version: Int,
    val source: PromptSource,
    val body: String,
    val vars: Set<String>,
)

/** 提示词页状态。 */
public data class PromptsUiState(
    val items: List<PromptItem> = emptyList(),
    val editing: PromptItem? = null,
    val editText: String = "",
    val editError: String? = null,
    val saving: Boolean = false,
)
