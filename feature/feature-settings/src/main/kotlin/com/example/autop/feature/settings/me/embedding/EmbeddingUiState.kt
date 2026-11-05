/**
 * 检索向量页状态（docs/tech/08 §4.5 `me/embedding`：提供方选择，记忆与对话历史共用；
 * 更换后显示"重建记忆索引 / 重建对话索引"进度）。
 */
package com.example.autop.feature.settings.me.embedding

import com.example.autop.core.config.LlmEmbeddingSection
import com.example.autop.llm.ModelProfile

/** 单路索引重建进度（docs/tech/06 §2.5 全量重建"设置页显示进度"）。 */
public data class ReindexProgress(
    val done: Int,
    val total: Int,
)

/** 两路索引重建进度（[memory] / [threads] 为 null 表示该路无重建在进行）。 */
public data class ReindexUiState(
    val memory: ReindexProgress? = null,
    val threads: ReindexProgress? = null,
)

/** 检索向量页状态。 */
public data class EmbeddingUiState(
    /** `llm.embedding` 当前段（配置默认 + 用户覆盖）。 */
    val section: LlmEmbeddingSection = LlmEmbeddingSection(),
    /** 可选的模型配置（provider=openai 时复用其 baseUrl 与密钥）。 */
    val profiles: List<ModelProfile> = emptyList(),
    val reindex: ReindexUiState = ReindexUiState(),
)
