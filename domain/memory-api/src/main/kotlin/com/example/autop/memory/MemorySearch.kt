/**
 * 记忆检索端口（docs/tech/06 §1）：通道扩展点、混合检索与 L0 索引渲染。
 */
package com.example.autop.memory

/** 检索通道扩展点。 */
public interface MemoryRetriever {
    /** 稳定 id（`retriever.path` | `retriever.fts` | `retriever.vector`）。 */
    public val id: String

    public suspend fun search(
        query: String,
        prefix: MemoryPath?,
        limit: Int,
    ): List<RankedPath>
}

/** 混合检索（多通道 RRF 融合）。 */
public interface MemorySearch {
    public suspend fun search(
        query: String,
        prefix: MemoryPath?,
        k: Int,
    ): List<SearchHit>
}

/** L0 索引渲染（`context.memory_index` 用）。 */
public interface MemoryIndexRenderer {
    public suspend fun renderL0(maxTokens: Int): String
}
