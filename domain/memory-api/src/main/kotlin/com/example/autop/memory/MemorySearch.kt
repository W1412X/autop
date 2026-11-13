/**
 * 记忆检索端口（ADR-0028）：检索通道扩展点、混合检索与 L0 索引渲染。
 */
package com.example.autop.memory

/**
 * 检索通道扩展点。
 *
 * [RetrievalHit.score] 为通道内原始分值（越大越相关），含义由通道自定（关键词通道是匹配字符数，
 * 向量通道是余弦相似度）；跨通道合并时由融合层按通道归一化。
 */
public interface MemoryRetriever {
    /** 稳定 id（`retriever.keyword` | `retriever.vector`）。 */
    public val id: String

    public suspend fun search(
        query: String,
        prefix: MemoryPath?,
        limit: Int,
    ): List<RetrievalHit>
}

/** 单个通道的一条候选。 */
public data class RetrievalHit(
    val path: MemoryPath,
    val score: Double,
)

/** 检索入口。 */
public interface MemorySearch {
    /** 混合检索：按 `memory.search.channels` 各通道加权合并，返回前 [k] 条。 */
    public suspend fun search(
        query: String,
        prefix: MemoryPath?,
        k: Int,
    ): List<SearchHit>

    /**
     * goal 预取（`context.memory_prefetch`）：只用关键词通道，原始分值低于 [minKeywordScore]
     * 的不返回；内置根目录与无内容的目录不返回。
     */
    public suspend fun prefetch(
        query: String,
        k: Int,
        minKeywordScore: Double,
    ): List<SearchHit>
}

/** L0 索引渲染（`context.memory_index` 用）。 */
public interface MemoryIndexRenderer {
    public suspend fun renderL0(maxTokens: Int): String
}
