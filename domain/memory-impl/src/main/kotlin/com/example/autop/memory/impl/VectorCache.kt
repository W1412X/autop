/**
 * 向量内存缓存（docs/tech/06 §2.6 `retriever.vector`）：启动时全量加载当前 modelId 的向量，
 * 写入时增量更新；检索时暴力计算余弦，取 top N，同一 path 多块取最高分。
 *
 * 纯 JVM，可直接单测（增量更新见 06 §7）。
 */
package com.example.autop.memory.impl

/** path → 各块向量。 */
public class VectorCache {
    private val byPath = LinkedHashMap<String, MutableList<FloatArray>>()
    private var model: String? = null

    public val modelId: String?
        get() = synchronized(this) { model }

    public val pathCount: Int
        get() = synchronized(this) { byPath.size }

    /** 全量加载（或 modelId 变化后重建）。 */
    @Synchronized
    fun load(
        modelId: String,
        entries: List<VectorEntry>,
    ) {
        byPath.clear()
        model = modelId
        entries.forEach { upsert(it.path, it.chunk, it.vector) }
    }

    /** 增量更新一块向量。 */
    @Synchronized
    fun upsert(
        path: String,
        chunk: Int,
        vector: FloatArray,
    ) {
        val chunks = byPath.getOrPut(path) { mutableListOf() }
        while (chunks.size <= chunk) chunks.add(FloatArray(0))
        chunks[chunk] = vector
    }

    /** 移除一个 path 的全部块。 */
    @Synchronized
    fun removePath(path: String) {
        byPath.remove(path)
    }

    /** 余弦相似度 top [k]（降序，同分按 path 稳定）。 */
    @Synchronized
    fun topK(
        query: FloatArray,
        k: Int,
    ): List<ScoredPath> =
        byPath
            .map { (path, chunks) -> ScoredPath(path, chunks.maxOfOrNull { cosine(it, query) } ?: 0.0) }
            .sortedWith(compareByDescending<ScoredPath> { it.score }.thenBy { it.path })
            .take(k)
}

/** 缓存加载项。 */
public data class VectorEntry(
    val path: String,
    val chunk: Int,
    val vector: FloatArray,
)

/** 相似度结果。 */
public data class ScoredPath(
    val path: String,
    val score: Double,
)

internal fun cosine(
    a: FloatArray,
    b: FloatArray,
): Double {
    if (a.isEmpty() || a.size != b.size) return 0.0
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        normA += a[i].toDouble() * a[i]
        normB += b[i].toDouble() * b[i]
    }
    val norms = Math.sqrt(normA) * Math.sqrt(normB)
    return if (norms <= 0.0) 0.0 else dot / norms
}
