/**
 * 检索向量页用户操作（docs/standards/coding-standards §8：sealed interface）。
 */
package com.example.autop.feature.settings.me.embedding

public sealed interface EmbeddingAction {
    /** 提供方（`none` / `openai`，docs/tech/03 §7）。 */
    public data class SetProvider(
        val id: String,
    ) : EmbeddingAction

    /** 复用的模型配置（null=不选）。 */
    public data class SetProfile(
        val id: String?,
    ) : EmbeddingAction

    /** 向量模型名。 */
    public data class SetModel(
        val value: String,
    ) : EmbeddingAction

    /** 重建记忆索引（docs/tech/10 WP10）。 */
    public data object ReindexMemory : EmbeddingAction

    /** 为历史对话重建向量索引（docs/tech/10 WP10）。 */
    public data object ReindexThreads : EmbeddingAction
}
