/**
 * 向量检索通道（docs/tech/04 §2.5）：`indexer.vector` 切块后经 `EmbeddingGateway`
 * 向量化写 `thread_event_vectors`（float32 小端 BLOB，带 modelId）；
 * `recall.vector` 读当前 Thread 全部向量暴力余弦取 top，同一 seq 多块取最高分。
 * embedding 未配置时索引器不处理事件、检索通道 `isAvailable()` 为 false。
 */
package com.example.autop.agent.store.recall

import com.example.autop.agent.RecallCandidate
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadEventIndexer
import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.agent.store.db.ThreadEventVectorEntity
import com.example.autop.agent.store.db.VectorDao
import com.example.autop.core.common.Clock
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.llm.EmbeddingGateway
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** float32 小端向量字节转换。 */
public object VectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
    }
}

/** 向量索引器。 */
public class VectorIndexer(
    private val vectors: VectorDao,
    private val embedding: EmbeddingGateway,
    private val snippets: SnippetReader,
    private val config: ConfigProvider,
    private val clock: Clock,
) : ThreadEventIndexer {
    override val id: String = "indexer.vector"

    override fun accepts(event: ThreadEvent): Boolean = true

    override suspend fun index(
        event: ThreadEvent,
        text: String,
    ) {
        val provider = embedding.current()
        val chunks = text.chunked(config.current()[AgentConfig.RECALL].vector.chunkChars)
        val embedded = if (provider == null || chunks.isEmpty()) null else provider.embed(chunks).getOrNull()
        if (provider == null || embedded == null) return
        val now = clock.nowMillis()
        embedded.forEachIndexed { i, vector ->
            vectors.insert(
                ThreadEventVectorEntity(
                    threadId = event.threadId.value,
                    seq = event.seq,
                    chunk = i,
                    modelId = provider.modelId,
                    dim = vector.size,
                    vector = VectorCodec.encode(vector),
                    createdAt = now,
                ),
            )
        }
    }

    override suspend fun deleteThread(threadId: ThreadId) {
        vectors.deleteThread(threadId.value)
    }

    override suspend fun rebuild(
        threadId: ThreadId,
        events: List<ThreadEvent>,
    ) {
        deleteThread(threadId)
        for (event in events) {
            val text = snippets.extract(event) ?: continue
            index(event, text)
        }
    }
}

/** 向量检索通道。 */
public class VectorRetriever(
    private val vectors: VectorDao,
    private val embedding: EmbeddingGateway,
    private val snippets: SnippetReader,
) : ThreadRecallRetriever {
    override val id: String = "recall.vector"

    override suspend fun isAvailable(): Boolean = embedding.current() != null

    override suspend fun search(
        threadId: ThreadId,
        query: String,
        limit: Int,
    ): List<RecallCandidate> {
        val queryVector =
            embedding
                .current()
                ?.embed(listOf(query))
                ?.getOrNull()
                ?.firstOrNull()
        return if (queryVector == null) {
            emptyList()
        } else {
            rank(threadId, queryVector, limit)
        }
    }

    private suspend fun rank(
        threadId: ThreadId,
        queryVector: FloatArray,
        limit: Int,
    ): List<RecallCandidate> =
        vectors
            .allOfThread(threadId.value)
            .asSequence()
            .map { it to cosine(queryVector, VectorCodec.decode(it.vector)) }
            .groupBy({ it.first.seq }, { it.second })
            .map { (seq, scores) -> seq to (scores.maxOrNull() ?: 0.0) }
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenByDescending { it.first })
            .take(limit)
            .mapIndexed { i, (seq, _) ->
                RecallCandidate(seq = seq, rank = i, snippet = snippets.snippet(threadId, seq))
            }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else dot / denominator
    }
}
