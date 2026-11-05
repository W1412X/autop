/**
 * `memory` 领域的配置段与键（docs/tech/09 §1.4）。
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

/** `memory.summary`：条目摘要。 */
@Serializable
public data class MemorySummarySection(
    @SerialName("maxChars") val maxChars: Int = 60,
    @SerialName("autoGenerate") val autoGenerate: Boolean = true,
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

/** `memory.embedding`：记忆切块与重试（embedding 提供方配置在 `llm.embedding`）。 */
@Serializable
public data class MemoryEmbeddingSection(
    @SerialName("chunkChars") val chunkChars: Int = 400,
    @SerialName("retryMax") val retryMax: Int = 3,
    @SerialName("retryBaseMs") val retryBaseMs: Long = 2000,
) : ConfigSection

/** `memory.search`：混合检索（RRF 融合）。 */
@Serializable
public data class MemorySearchSection(
    @SerialName("channels") val channels: List<String> = DEFAULT_CHANNELS,
    @SerialName("weights") val weights: Map<String, Double> = DEFAULT_WEIGHTS,
    @SerialName("rrfK") val rrfK: Int = 60,
    @SerialName("perChannelLimit") val perChannelLimit: Int = 30,
    @SerialName("vectorOverfetch") val vectorOverfetch: Int = 3,
    @SerialName("defaultK") val defaultK: Int = 8,
) : ConfigSection {
    public companion object {
        public val DEFAULT_CHANNELS: List<String> = listOf("retriever.path", "retriever.fts", "retriever.vector")
        public val DEFAULT_WEIGHTS: Map<String, Double> =
            mapOf("retriever.path" to 1.2, "retriever.fts" to 1.0, "retriever.vector" to 1.0)
    }
}

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

/** `memory.triggerPrefetch`：事件触发时预取记忆条数。 */
@Serializable
public data class MemoryTriggerPrefetchSection(
    @SerialName("k") val k: Int = 3,
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
    public val EMBEDDING: ConfigKey<MemoryEmbeddingSection> =
        ConfigKey("memory.embedding", MemoryEmbeddingSection.serializer()) { MemoryEmbeddingSection() }
    public val SEARCH: ConfigKey<MemorySearchSection> =
        ConfigKey("memory.search", MemorySearchSection.serializer()) { MemorySearchSection() }
    public val L0: ConfigKey<MemoryL0Section> =
        ConfigKey("memory.l0", MemoryL0Section.serializer()) { MemoryL0Section() }
    public val PROFILE: ConfigKey<MemoryProfileSection> =
        ConfigKey("memory.profile", MemoryProfileSection.serializer()) { MemoryProfileSection() }
    public val LIST: ConfigKey<MemoryListSection> =
        ConfigKey("memory.list", MemoryListSection.serializer()) { MemoryListSection() }
    public val TRIGGER_PREFETCH: ConfigKey<MemoryTriggerPrefetchSection> =
        ConfigKey(
            "memory.triggerPrefetch",
            MemoryTriggerPrefetchSection.serializer(),
        ) { MemoryTriggerPrefetchSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(PATH, SUMMARY, NODE, WRITE, EMBEDDING, SEARCH, L0, PROFILE, LIST, TRIGGER_PREFETCH)
}
