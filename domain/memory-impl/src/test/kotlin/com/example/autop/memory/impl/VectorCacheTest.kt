/**
 * VectorCache 测试（docs/tech/06 §2.6/§7）：增量更新与余弦 top K。
 */
package com.example.autop.memory.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 向量内存缓存行为测试。 */
public class VectorCacheTest {
    @Test
    public fun `load replaces previous contents`() {
        val cache = VectorCache()
        cache.load("m1", listOf(VectorEntry("/a", 0, floatArrayOf(1.0F, 0.0F))))

        cache.load("m2", listOf(VectorEntry("/b", 0, floatArrayOf(0.0F, 1.0F))))

        assertThat(cache.modelId).isEqualTo("m2")
        assertThat(cache.pathCount).isEqualTo(1)
        assertThat(cache.topK(floatArrayOf(0.0F, 1.0F), 5).map { it.path }).containsExactly("/b")
    }

    @Test
    public fun `upsert updates one chunk incrementally`() {
        val cache = VectorCache()
        cache.load("m1", listOf(VectorEntry("/a", 0, floatArrayOf(1.0F, 0.0F))))

        cache.upsert("/a", 0, floatArrayOf(0.0F, 1.0F))
        cache.upsert("/b", 0, floatArrayOf(1.0F, 0.0F))

        assertThat(cache.pathCount).isEqualTo(2)
        assertThat(cache.topK(floatArrayOf(0.0F, 1.0F), 2).first().path).isEqualTo("/a")
    }

    @Test
    public fun `removePath drops all chunks of a path`() {
        val cache = VectorCache()
        cache.load("m1", listOf(VectorEntry("/a", 0, floatArrayOf(1.0F)), VectorEntry("/a", 1, floatArrayOf(0.5F))))

        cache.removePath("/a")

        assertThat(cache.pathCount).isEqualTo(0)
        assertThat(cache.topK(floatArrayOf(1.0F), 5)).isEmpty()
    }

    @Test
    public fun `topK takes best chunk score per path and sorts descending`() {
        val cache = VectorCache()
        cache.load(
            "m1",
            listOf(
                VectorEntry("/low", 0, floatArrayOf(1.0F, 0.0F)),
                VectorEntry("/high", 0, floatArrayOf(0.0F, 0.1F)),
                VectorEntry("/high", 1, floatArrayOf(0.0F, 1.0F)),
            ),
        )

        val hits = cache.topK(floatArrayOf(0.0F, 1.0F), 2)

        assertThat(hits.map { it.path }).containsExactly("/high", "/low").inOrder()
    }

    @Test
    public fun `cosine of mismatched or empty vectors is zero`() {
        val cache = VectorCache()
        cache.load("m1", listOf(VectorEntry("/a", 0, FloatArray(0))))

        val hits = cache.topK(floatArrayOf(1.0F, 2.0F), 1)

        assertThat(hits.single().score).isEqualTo(0.0)
    }
}
