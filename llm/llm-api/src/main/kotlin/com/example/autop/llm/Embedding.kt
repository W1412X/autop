/**
 * Embedding 端口（docs/tech/03 §7）：使用方（记忆检索、历史检索）只依赖 [EmbeddingGateway]。
 */
package com.example.autop.llm

import com.example.autop.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 文本向量化提供者。 */
public interface EmbeddingProvider {
    /** 写入向量记录；切换模型后据此判断需要重建索引。 */
    public val modelId: String

    public val dimension: Int

    public suspend fun embed(texts: List<String>): Outcome<List<FloatArray>>
}

/** [EmbeddingProvider] 工厂（扩展点，按 [id] 匹配 `llm.embedding.provider`）。 */
public interface EmbeddingProviderFactory {
    public val id: String

    public fun create(config: EmbeddingConfig): EmbeddingProvider
}

/**
 * Embedding 网关：按配置返回当前 provider，未配置返回 null；
 * 配置变化时 [changes] 发新实例（modelId 随之变化）。
 */
public interface EmbeddingGateway {
    public fun current(): EmbeddingProvider?

    public val changes: Flow<EmbeddingProvider?>
}

/** `llm.embedding` 配置（docs/tech/03 §7）。 */
@Serializable
public data class EmbeddingConfig(
    @SerialName("provider") val provider: String,
    @SerialName("profileId") val profileId: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("dimension") val dimension: Int? = null,
    @SerialName("batchSize") val batchSize: Int = 16,
)
