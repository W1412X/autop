/**
 * `retriever.vector` 通道（docs/tech/06 §2.6）：query 向量化后与内存缓存暴力计算余弦，
 * 取 top `k × vectorOverfetch`；同一 path 多块取最高分。未配置 embedding 时返回空。
 */
package com.example.autop.memory.impl.retriever

import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.RankedPath
import com.example.autop.memory.impl.VectorCache
import com.example.autop.memory.impl.VectorEntry
import com.example.autop.memory.impl.db.MemoryDb
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
        ): List<RankedPath> {
            val provider = gateway.current() ?: return emptyList()
            if (cache.modelId != provider.modelId) reload(provider.modelId)
            val queryVector =
                provider.embed(listOf(query)).getOrNull()?.firstOrNull() ?: return emptyList()
            val overfetch = configProvider.current()[MemoryConfig.SEARCH].vectorOverfetch
            return cache
                .topK(queryVector, limit * overfetch)
                .filter { prefix == null || it.path.startsWith(prefix.value) }
                .take(limit)
                .mapIndexed { i, scored ->
                    RankedPath(
                        path = MemoryPath.parse(scored.path).getOrNull() ?: MemoryPath.ROOT,
                        rank = i + 1,
                        snippet = null,
                    )
                }
        }

        /** 从表中全量加载当前 modelId 的向量到缓存。 */
        public suspend fun reload(modelId: String) {
            val rows = db.vectorDao().byModel(modelId)
            cache.load(
                modelId,
                rows.map {
                    VectorEntry(path = it.path, chunk = it.chunk, vector = it.vector.toFloatArray())
                },
            )
        }

        private companion object {
            const val ID: String = "retriever.vector"
        }
    }

private fun ByteArray.toFloatArray(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(size / Float.SIZE_BYTES)
    for (i in out.indices) out[i] = buf.float
    return out
}
