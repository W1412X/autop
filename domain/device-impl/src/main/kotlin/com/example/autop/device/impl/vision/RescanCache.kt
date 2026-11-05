/**
 * 补扫框级缓存（vp9 §4.7，纯 Kotlin 可单测）：键＝（类别，框 8px 量化坐标，裁剪区 64×64
 * 平均哈希，复用 [VisualFrameDiff.signature]）；LRU 容量 `cacheSize`；rotation 变化整体清空。
 * 整帧不变的整树复用由 VisualFrameDiff 管，不在此层。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect

/** 框级缓存键。 */
internal data class RescanCacheKey(
    val category: String,
    val quantized: String,
    val hash: Long,
)

/** 框级 LRU 缓存（补扫行、图标结果）。 */
internal class RescanCache(
    private val capacity: Int,
) {
    private val rescanLines = LinkedHashMap<RescanCacheKey, List<VisualOcrLine>>(capacity, LOAD_FACTOR, true)
    private val iconLabels = LinkedHashMap<RescanCacheKey, String>(capacity, LOAD_FACTOR, true)

    /** 补扫行缓存（命中刷新 LRU）。 */
    fun rescanOf(key: RescanCacheKey): List<VisualOcrLine>? = rescanLines[key]

    fun putRescan(
        key: RescanCacheKey,
        lines: List<VisualOcrLine>,
    ) {
        rescanLines[key] = lines
        evict(rescanLines)
    }

    /** 图标结果缓存。 */
    fun iconOf(key: RescanCacheKey): String? = iconLabels[key]

    fun putIcon(
        key: RescanCacheKey,
        label: String,
    ) {
        iconLabels[key] = label
        evict(iconLabels)
    }

    /** rotation 变化整体清空（§4.7）。 */
    fun clear() {
        rescanLines.clear()
        iconLabels.clear()
    }

    private fun <T> evict(map: LinkedHashMap<RescanCacheKey, T>) {
        while (map.size > capacity) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
    }

    internal companion object {
        private const val LOAD_FACTOR: Float = 0.75f

        /** 坐标量化步长（§4.7：8px）。 */
        private const val QUANTIZE: Int = 8

        /** ARGB 通道位移与掩码。 */
        private const val RED_SHIFT: Int = 16
        private const val GREEN_SHIFT: Int = 8
        private const val BYTE_MASK: Int = 0xFF

        /** BT.601 灰度权重（整数近似）。 */
        private const val LUMA_R: Int = 299
        private const val LUMA_G: Int = 587
        private const val LUMA_B: Int = 114
        private const val LUMA_SCALE: Int = 1000

        /** 构造缓存键：类别＋框 8px 量化坐标＋裁剪区 aHash。 */
        fun keyOf(
            category: String,
            bounds: Rect,
            cropGray: IntArray,
        ): RescanCacheKey {
            val q = listOf(bounds.l, bounds.t, bounds.r, bounds.b).map { it / QUANTIZE }
            val hash = VisualFrameDiff.signature(cropGray).hash
            return RescanCacheKey(category, q.joinToString(","), hash)
        }

        /** 裁剪区 → [VisualFrameDiff.GRAY_EDGE]² 灰度降采样（近邻，aHash 输入）。 */
        fun grayOf(
            image: VisionImage,
            region: Rect,
        ): IntArray {
            val edge = VisualFrameDiff.GRAY_EDGE
            val gray = IntArray(edge * edge)
            val l = region.l.coerceIn(0, image.width - 1)
            val t = region.t.coerceIn(0, image.height - 1)
            val r = region.r.coerceIn(l + 1, image.width)
            val b = region.b.coerceIn(t + 1, image.height)
            for (y in 0 until edge) {
                val sy = t + y * (b - t) / edge
                for (x in 0 until edge) {
                    val sx = l + x * (r - l) / edge
                    val p = image.argb[sy * image.width + sx]
                    val red = (p shr RED_SHIFT) and BYTE_MASK
                    val green = (p shr GREEN_SHIFT) and BYTE_MASK
                    val blue = p and BYTE_MASK
                    gray[y * edge + x] = (red * LUMA_R + green * LUMA_G + blue * LUMA_B) / LUMA_SCALE
                }
            }
            return gray
        }
    }
}
