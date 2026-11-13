/**
 * `retriever.vector` 通道（ADR-0028）：查询向量化后与内存缓存暴力计算余弦相似度。
 * 每个节点只有一个向量，输入为标题与关键词；低于 `memory.vector.minScore` 的结果丢弃。
 * 未配置 embedding 时返回空，检索退化为只用关键词。
 */
package com.example.autop.memory.impl.retriever

import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.RetrievalHit
import com.example.autop.memory.impl.MemoryKeywords
import com.example.autop.memory.impl.VectorCache
import com.example.autop.memory.impl.VectorEntry
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.isWithinPath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** 向量语义通道。 */
@Singleton
public class VectorRetriever
    @Inject
    constructor(
        private val db: MemoryDb,
        private val gateway: EmbeddingGateway,
        private val cache: VectorCache,
        private val configProvider: ConfigProvider,
    ) : MemoryRetriever {
        override val id: String = ID

        @Suppress("ReturnCount") // 出口按“未配置/向量化失败”分支，是流程语义
        override suspend fun search(
            query: String,
            prefix: MemoryPath?,
            limit: Int,
        ): List<RetrievalHit> {
            val provider = gateway.current() ?: return emptyList()
            if (cache.modelId != provider.modelId) reload(provider.modelId)
            val text = MemoryKeywords.normalize(query)
            if (text.isEmpty()) return emptyList()
            val queryVector = provider.embed(listOf(text)).getOrNull()?.firstOrNull() ?: return emptyList()
            val cfg = configProvider.current()[MemoryConfig.VECTOR]
            return cache
                .topK(queryVector, cfg.topN)
                .asSequence()
                .filter { it.score >= cfg.minScore }
                .filter { prefix == null || it.path.isWithinPath(prefix.value) }
                .take(limit)
                .mapNotNull { hit -> MemoryPath.parse(hit.path).getOrNull()?.let { RetrievalHit(it, hit.score) } }
                .toList()
        }

        /** 从表中全量加载当前 modelId 的向量到缓存。 */
        public suspend fun reload(modelId: String) {
            val rows = db.vectorDao().byModel(modelId)
            cache.load(
                modelId,
                rows.map { VectorEntry(path = it.path, chunk = it.chunk, vector = it.vector.toFloatArray()) },
            )
        }

        public companion object {
            public const val ID: String = "retriever.vector"
        }
    }

private fun ByteArray.toFloatArray(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(size / Float.SIZE_BYTES)
    for (i in out.indices) out[i] = buf.float
    return out
}
