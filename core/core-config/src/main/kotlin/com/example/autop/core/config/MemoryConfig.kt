/**
 * `memory` 领域的配置段与键（docs/tech/09 §1.4，ADR-0028）。
 * 段默认值与 `core-config-impl/assets/config/memory.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `memory.path`：记忆路径规则。 */
@Serializable
public data class MemoryPathSection(
    @SerialName("maxSegmentChars") val maxSegmentChars: Int = 64,
    @SerialName("maxDepth") val maxDepth: Int = 8,
) : ConfigSection

/** `memory.summary`：条目摘要长度上限（摘要用于自动注入与列表展示）。 */
@Serializable
public data class MemorySummarySection(
    @SerialName("maxChars") val maxChars: Int = 60,
) : ConfigSection

/** `memory.node`：条目内容上限。 */
@Serializable
public data class MemoryNodeSection(
    @SerialName("maxContentChars") val maxContentChars: Int = 20000,
) : ConfigSection

/** `memory.write`：写入保护（用户内容补充段模板）。 */
@Serializable
public data class MemoryWriteSection(
    @SerialName("supplementPromptId") val supplementPromptId: String = "memory.write.supplement",
) : ConfigSection

/** `memory.keywords`：检索关键词的数量与长度上限（关键词只用于检索）。 */
@Serializable
public data class MemoryKeywordsSection(
    @SerialName("maxCount") val maxCount: Int = 12,
    @SerialName("maxChars") val maxChars: Int = 20,
) : ConfigSection

/** `memory.embedding`：向量化失败的重试策略（provider 配置在 `llm.embedding`）。 */
@Serializable
public data class MemoryEmbeddingSection(
    @SerialName("retryMax") val retryMax: Int = 3,
    @SerialName("retryBaseMs") val retryBaseMs: Long = 2000,
) : ConfigSection

/**
 * `memory.search`：混合检索。通道权重只用于合并，各通道原始分值先按通道内最大值归一化。
 * 关键词长度下限避免 1 字中文、2 字符以内英文等过短词造成误命中。
 */
@Serializable
public data class MemorySearchSection(
    @SerialName("channels") val channels: List<String> = DEFAULT_CHANNELS,
    @SerialName("weights") val weights: Map<String, Double> = DEFAULT_WEIGHTS,
    @SerialName("perChannelLimit") val perChannelLimit: Int = 30,
    @SerialName("defaultK") val defaultK: Int = 8,
    @SerialName("minCjkChars") val minCjkChars: Int = 2,
    @SerialName("minAsciiChars") val minAsciiChars: Int = 3,
) : ConfigSection {
    public companion object {
        public val DEFAULT_CHANNELS: List<String> = listOf("retriever.keyword", "retriever.vector")
        public val DEFAULT_WEIGHTS: Map<String, Double> = mapOf("retriever.keyword" to 0.6, "retriever.vector" to 0.4)
    }
}

/** `memory.vector`：向量通道（输入为标题与关键词，一个节点一个向量）。 */
@Serializable
public data class MemoryVectorSection(
    /** 余弦相似度下限，低于此值的结果直接丢弃（需真机标定）。 */
    @SerialName("minScore") val minScore: Double = 0.55,
    /** 向量通道最多返回的候选数。 */
    @SerialName("topN") val topN: Int = 20,
) : ConfigSection

/** `memory.l0`：L0 索引层（目录树）。 */
@Serializable
public data class MemoryL0Section(
    @SerialName("maxTokens") val maxTokens: Int = 1500,
    @SerialName("maxChildrenPerRoot") val maxChildrenPerRoot: Int = 8,
) : ConfigSection

/** `memory.profile`：用户画像段。 */
@Serializable
public data class MemoryProfileSection(
    @SerialName("maxTokens") val maxTokens: Int = 300,
) : ConfigSection

/** `memory.list`：目录列出深度。 */
@Serializable
public data class MemoryListSection(
    @SerialName("maxDepth") val maxDepth: Int = 3,
) : ConfigSection

/**
 * `memory.prefetch`：goal 预取（`context.memory_prefetch`）。只注入摘要，篇幅小，
 * 没有命中就不注入。
 */
@Serializable
public data class MemoryPrefetchSection(
    /** 最多注入条数。 */
    @SerialName("k") val k: Int = 3,
    /** 注入文本总长上限（字符）。 */
    @SerialName("maxChars") val maxChars: Int = 300,
    /** 关键词通道原始分值下限（匹配字符数之和），低于此值视为无关。 */
    @SerialName("minKeywordScore") val minKeywordScore: Double = 2.0,
) : ConfigSection

/** `memory` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object MemoryConfig {
    public val PATH: ConfigKey<MemoryPathSection> =
        ConfigKey("memory.path", MemoryPathSection.serializer()) { MemoryPathSection() }
    public val SUMMARY: ConfigKey<MemorySummarySection> =
        ConfigKey("memory.summary", MemorySummarySection.serializer()) { MemorySummarySection() }
    public val NODE: ConfigKey<MemoryNodeSection> =
        ConfigKey("memory.node", MemoryNodeSection.serializer()) { MemoryNodeSection() }
    public val WRITE: ConfigKey<MemoryWriteSection> =
        ConfigKey("memory.write", MemoryWriteSection.serializer()) { MemoryWriteSection() }
    public val KEYWORDS: ConfigKey<MemoryKeywordsSection> =
        ConfigKey("memory.keywords", MemoryKeywordsSection.serializer()) { MemoryKeywordsSection() }
    public val EMBEDDING: ConfigKey<MemoryEmbeddingSection> =
        ConfigKey("memory.embedding", MemoryEmbeddingSection.serializer()) { MemoryEmbeddingSection() }
    public val SEARCH: ConfigKey<MemorySearchSection> =
        ConfigKey("memory.search", MemorySearchSection.serializer()) { MemorySearchSection() }
    public val VECTOR: ConfigKey<MemoryVectorSection> =
        ConfigKey("memory.vector", MemoryVectorSection.serializer()) { MemoryVectorSection() }
    public val L0: ConfigKey<MemoryL0Section> =
        ConfigKey("memory.l0", MemoryL0Section.serializer()) { MemoryL0Section() }
    public val PROFILE: ConfigKey<MemoryProfileSection> =
        ConfigKey("memory.profile", MemoryProfileSection.serializer()) { MemoryProfileSection() }
    public val LIST: ConfigKey<MemoryListSection> =
        ConfigKey("memory.list", MemoryListSection.serializer()) { MemoryListSection() }
    public val PREFETCH: ConfigKey<MemoryPrefetchSection> =
        ConfigKey("memory.prefetch", MemoryPrefetchSection.serializer()) { MemoryPrefetchSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(PATH, SUMMARY, NODE, WRITE, KEYWORDS, EMBEDDING, SEARCH, VECTOR, L0, PROFILE, LIST, PREFETCH)
}
