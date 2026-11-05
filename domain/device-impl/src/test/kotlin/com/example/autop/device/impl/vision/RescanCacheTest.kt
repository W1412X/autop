package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 补扫缓存（vp9 §4.7）：键一致、LRU 淘汰、rotation 清空。 */
internal class RescanCacheTest {
    private val gray = IntArray(RescanGrayEdge.SQUARED) { it % 255 }

    private fun key(x: Int) = RescanCache.keyOf("button", Rect(x, 0, x + 10, 10), gray)

    @Test
    fun `same category box and hash give same key`() {
        assertThat(key(10)).isEqualTo(key(10))
        assertThat(key(10)).isNotEqualTo(key(200))
    }

    @Test
    fun `lru evicts eldest beyond capacity`() {
        val cache = RescanCache(2)
        cache.putRescan(key(10), listOf(VisualOcrLine("a", 0.9, Rect(0, 0, 5, 5))))
        cache.putRescan(key(200), listOf(VisualOcrLine("b", 0.9, Rect(0, 0, 5, 5))))
        cache.putRescan(key(400), listOf(VisualOcrLine("c", 0.9, Rect(0, 0, 5, 5))))
        assertThat(cache.rescanOf(key(10))).isNull()
        assertThat(cache.rescanOf(key(200))).isNotNull()
        assertThat(cache.rescanOf(key(400))).isNotNull()
    }

    @Test
    fun `clear wipes rescan and icon entries`() {
        val cache = RescanCache(4)
        cache.putRescan(key(10), listOf(VisualOcrLine("a", 0.9, Rect(0, 0, 5, 5))))
        cache.putIcon(key(10), "搜索")
        cache.clear()
        assertThat(cache.rescanOf(key(10))).isNull()
        assertThat(cache.iconOf(key(10))).isNull()
    }
}

/** grayOf 输出边长平方（测试用）。 */
private object RescanGrayEdge {
    const val SQUARED: Int = 64 * 64
}
