/**
 * 标签栏几何识别（vp8 §8.2，ADR-0008 决策 7）：屏幕顶部或底部 12% 内，一行 ≥3 个
 * 尺寸相近（宽度差 ≤15%）的 item/button/icon → 一组标签栏，树构建阶段合成公共父节点。
 * 纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.abs

/** 标签栏候选项（类别＋框）。 */
internal class TabCandidate(
    val category: String,
    val bounds: Rect,
)

/** 标签栏几何识别器。 */
internal object TabBarGrouper {
    /** 边缘带：屏幕顶部/底部各 12%。 */
    private const val EDGE_RATIO: Double = 0.12

    /** 一组标签栏最少项数。 */
    private const val MIN_TABS: Int = 3

    /** 宽度相近判定：(最宽-最窄)/均宽 ≤ 15%。 */
    private const val WIDTH_SPREAD: Double = 0.15

    /** 行带宽度（与 VisualTreeBuilder 阅读序同口径）。 */
    private const val ROW_BAND_RATIO: Double = 0.6
    private const val ROW_BAND_MIN: Int = 8

    /** 候选类别（vp8 §8.2：item/button/icon）。 */
    private val CATEGORIES: Set<String> =
        setOf(
            VisualCategory.ITEM,
            VisualCategory.LIST_ITEM,
            VisualCategory.BUTTON,
            VisualCategory.ICON,
            VisualCategory.ICON_CLICKABLE,
        )

    /**
     * 识别标签栏组：返回每组的候选下标（按 x 阅读序）。条件：同行（y 中心带）内
     * ≥[MIN_TABS] 个候选、宽度差 ≤[WIDTH_SPREAD]、整行位于顶部或底部边缘带内。
     */
    fun group(
        candidates: List<TabCandidate>,
        screenHeight: Int,
    ): List<List<Int>> {
        val hits = candidates.withIndex().filter { it.value.category in CATEGORIES }
        if (hits.size < MIN_TABS) return emptyList()
        val band = bandOf(hits.map { it.value })
        return rowsOf(hits, band)
            .filter { inEdgeBand(it, screenHeight) && similarWidth(it) }
            .map { row -> row.sortedBy { it.value.bounds.centerX }.map { it.index } }
    }

    /** 行带宽度：候选高中位数高度 ×0.6（下限 8px）。 */
    private fun bandOf(items: List<TabCandidate>): Int {
        val heights = items.map { it.bounds.height }.sorted()
        return maxOf(ROW_BAND_MIN, (ROW_BAND_RATIO * heights[heights.size / 2]).toInt())
    }

    /** y 中心带聚类成行（出现顺序即行序）。 */
    private fun rowsOf(
        hits: List<IndexedValue<TabCandidate>>,
        band: Int,
    ): List<List<IndexedValue<TabCandidate>>> {
        val rows = mutableListOf<MutableList<IndexedValue<TabCandidate>>>()
        for (hit in hits.sortedBy { it.value.bounds.centerY }) {
            val row =
                rows.firstOrNull {
                    abs(
                        hit.value.bounds.centerY -
                            it
                                .first()
                                .value.bounds.centerY,
                    ) <= band
                }
            (row ?: mutableListOf<IndexedValue<TabCandidate>>().also { rows += it }) += hit
        }
        return rows
    }

    /** 整行中心位于屏幕顶部/底部 [EDGE_RATIO] 带内。 */
    private fun inEdgeBand(
        row: List<IndexedValue<TabCandidate>>,
        screenHeight: Int,
    ): Boolean {
        val edge = EDGE_RATIO * screenHeight
        return row.all { it.value.bounds.centerY <= edge || it.value.bounds.centerY >= screenHeight - edge }
    }

    /** 宽度相近：(最宽-最窄)/均宽 ≤ [WIDTH_SPREAD]。 */
    private fun similarWidth(row: List<IndexedValue<TabCandidate>>): Boolean {
        val widths = row.map { it.value.bounds.width }
        val mean = widths.average()
        return mean > 0 && (widths.max() - widths.min()) / mean <= WIDTH_SPREAD
    }
}
