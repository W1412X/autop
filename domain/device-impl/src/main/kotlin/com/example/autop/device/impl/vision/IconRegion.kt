/**
 * 图标裁图区域（vp9 §5.2，纯 Kotlin 可单测）：吸收所得 `iconRegion` 优先；icon 叶子整框；
 * 叶子有归属文字时按列投影取最宽无文字列块（宽 < `minIconSide×框高` 不推理）；无文字整框。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect

/** 图标裁图区域选择器。 */
internal object IconRegion {
    /**
     * 候选叶子的裁图区域（vp9 §5.2）；返回 null 表示不推理（文字列块过窄）。
     * [textLines] 为该叶子的归属文字行（屏幕坐标），[iconRegion] 为吸收所得子框。
     */
    fun cropRegion(
        box: Rect,
        iconRegion: Rect?,
        textLines: List<Rect>,
        minIconSide: Double,
    ): Rect? =
        when {
            iconRegion != null -> iconRegion
            textLines.isEmpty() -> box
            else -> widestFreeColumns(box, textLines, minIconSide)
        }

    /** 列投影：找最宽无文字列区间（§5.2），宽 < [minIconSide]×框高 返回 null。 */
    private fun widestFreeColumns(
        box: Rect,
        textLines: List<Rect>,
        minIconSide: Double,
    ): Rect? {
        val covered = BooleanArray(box.width)
        for (line in textLines) {
            val l = maxOf(box.l, line.l)
            val r = minOf(box.r, line.r)
            for (x in l until r) covered[x - box.l] = true
        }
        var bestStart = -1
        var bestWidth = 0
        var start = -1
        for (i in covered.indices) {
            if (!covered[i]) {
                if (start < 0) start = i
                if (i - start + 1 > bestWidth) {
                    bestWidth = i - start + 1
                    bestStart = start
                }
            } else {
                start = -1
            }
        }
        val minWidth = minIconSide * box.height
        if (bestStart < 0 || bestWidth < minWidth) return null
        return Rect(box.l + bestStart, box.t, box.l + bestStart + bestWidth, box.b)
    }
}
