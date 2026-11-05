/**
 * input 提示语判定（vp9 §4.6，纯 Kotlin 可单测）：input 内文字前景与背景亮度差 <
 * `hintContrast` → 写 `hint`（`text=""`），否则写 `text`。背景＝框边缘一圈中位亮度，
 * 前景＝与背景差最大的 10% 像素中位亮度。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect

/** input 提示语判定器。 */
internal object InputHintDetector {
    /** 前景采样分位（§4.6：与背景差最大的 10% 像素）。 */
    private const val TOP_FRACTION: Double = 0.1

    /** BT.601 灰度权重（整数近似）。 */
    private const val LUMA_R: Int = 299
    private const val LUMA_G: Int = 587
    private const val LUMA_B: Int = 114
    private const val LUMA_SCALE: Int = 1000

    /** ARGB 通道位移与掩码。 */
    private const val RED_SHIFT: Int = 16
    private const val GREEN_SHIFT: Int = 8
    private const val BYTE_MASK: Int = 0xFF

    /** 亮度差阈值以下判为提示语（§4.6）。 */
    fun isHint(
        image: VisionImage,
        line: Rect,
        hintContrast: Int,
    ): Boolean {
        val l = line.l.coerceIn(0, image.width - 1)
        val r = line.r.coerceIn(l + 1, image.width)
        val t = line.t.coerceIn(0, image.height - 1)
        val b = line.b.coerceIn(t + 1, image.height)
        val background = edgeMedian(image, l, t, r, b)
        val foreground = foregroundMedian(image, l, t, r, b, background)
        return kotlin.math.abs(foreground - background) < hintContrast
    }

    /** 框边缘一圈像素的中位亮度。 */
    private fun edgeMedian(
        image: VisionImage,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): Int {
        val edge = mutableListOf<Int>()
        for (x in l until r) {
            edge += luma(image, x, t)
            edge += luma(image, x, b - 1)
        }
        for (y in t + 1 until b - 1) {
            edge += luma(image, l, y)
            edge += luma(image, r - 1, y)
        }
        return edge.sorted().let { it[it.size / 2] }
    }

    /** 与背景差最大的 10% 像素的中位亮度。 */
    private fun foregroundMedian(
        image: VisionImage,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        background: Int,
    ): Int {
        val diffs = mutableListOf<Pair<Int, Int>>()
        for (y in t until b) {
            for (x in l until r) {
                val v = luma(image, x, y)
                diffs += (kotlin.math.abs(v - background) to v)
            }
        }
        val top = diffs.sortedByDescending { it.first }.take((diffs.size * TOP_FRACTION).toInt().coerceAtLeast(1))
        return top.map { it.second }.sorted().let { it[it.size / 2] }
    }

    /** 灰度亮度（BT.601 整数近似）。 */
    private fun luma(
        image: VisionImage,
        x: Int,
        y: Int,
    ): Int {
        val p = image.argb[y * image.width + x]
        val red = (p shr RED_SHIFT) and BYTE_MASK
        val green = (p shr GREEN_SHIFT) and BYTE_MASK
        val blue = p and BYTE_MASK
        return (red * LUMA_R + green * LUMA_G + blue * LUMA_B) / LUMA_SCALE
    }
}
