/**
 * 两次观测的节点配对（agent-harness-overhaul D5）：差量观测与屏幕变化结论共用。
 * 配对不依赖稳定键里的位置路径，视觉识别顺序变化不会产生“同一文字新增+消失”。
 */
package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.ScreenNode
import kotlin.math.abs

/** 节点配对器。算法（第 1/2 轮 + 状态变化）见 [match]。 */
public object ScreenNodeMatcher {
    /** 配对结果。changed 取当前侧节点。 */
    public data class Result(
        val added: List<ScreenNode>,
        val removed: List<ScreenNode>,
        val changed: List<ScreenNode>,
    ) {
        public val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
        public val count: Int get() = added.size + removed.size + changed.size
    }

    /**
     * 按“内容 + 位置容差”配对：
     * 1. 每个节点取 `label = stripVolatile(text ?: desc ?: icon ?: hint ?: "")` 与中心点；
     * 2. 第一轮遍历 cur，在 prev 未配对节点中找 tag、label 相同且 |Δx|、|Δy| 均 ≤ [tolerance] 的节点，
     *    取中心距离最小者（相同取 prev 下标最小）；
     * 3. 第二轮对剩余 cur，找 tag 相同且位置在容差内（不要求 label 相同）的节点，配上的计入 changed；
     * 4. 剩余 cur 计入 added、剩余 prev 计入 removed；
     * 5. 第一轮配上但 act/st/state 不同的也计入 changed。
     */
    public fun match(
        prev: List<ScreenNode>,
        cur: List<ScreenNode>,
        tolerance: Int,
        volatilePatterns: List<Regex>,
    ): Result {
        val prevUsed = BooleanArray(prev.size)
        val curUsed = BooleanArray(cur.size)
        val changed = mutableListOf<ScreenNode>()
        val prevCenters = prev.map(::centerOf)
        val curCenters = cur.map(::centerOf)
        val prevLabels = prev.map { labelOf(it, volatilePatterns) }
        val curLabels = cur.map { labelOf(it, volatilePatterns) }
        val byContent = Candidates(prev, prevCenters, prevLabels, prevUsed, tolerance)
        val byPosition = Candidates(prev, prevCenters, null, prevUsed, tolerance)

        for (ci in cur.indices) {
            val pi = nearest(cur[ci].tag, curCenters[ci], curLabels[ci], byContent)
            if (pi >= 0) {
                prevUsed[pi] = true
                curUsed[ci] = true
                if (stateChanged(prev[pi], cur[ci])) changed += cur[ci]
            }
        }

        for (ci in cur.indices) {
            if (curUsed[ci]) continue
            val pi = nearest(cur[ci].tag, curCenters[ci], null, byPosition)
            if (pi >= 0) {
                prevUsed[pi] = true
                curUsed[ci] = true
                changed += cur[ci]
            }
        }

        return Result(
            added = cur.filterIndexed { i, _ -> !curUsed[i] },
            removed = prev.filterIndexed { i, _ -> !prevUsed[i] },
            changed = changed,
        )
    }

    /** 一轮配对的候选集合（用于控制参数个数）。 */
    private class Candidates(
        val prev: List<ScreenNode>,
        val centers: List<Point>,
        val labels: List<String>?,
        val used: BooleanArray,
        val tolerance: Int,
    )

    /** 未配对 prev 中符合条件、中心距离最近的节点；[curLabel] 为 null 时不比较 label。 */
    private fun nearest(
        tag: String,
        curCenter: Point,
        curLabel: String?,
        candidates: Candidates,
    ): Int {
        val nearest =
            candidates.prev.indices
                .filter { usable(it, tag, curLabel, candidates) }
                .mapNotNull { pi -> distance(pi, curCenter, candidates)?.let { dist -> pi to dist } }
                .minByOrNull { (_, dist) -> dist }
        return nearest?.first ?: -1
    }

    private fun usable(
        pi: Int,
        tag: String,
        curLabel: String?,
        candidates: Candidates,
    ): Boolean {
        val labelOk =
            candidates.labels == null || curLabel == null || candidates.labels[pi] == curLabel
        return !candidates.used[pi] && candidates.prev[pi].tag == tag && labelOk
    }

    private fun distance(
        pi: Int,
        curCenter: Point,
        candidates: Candidates,
    ): Int? {
        val dx = abs(candidates.centers[pi].x - curCenter.x)
        val dy = abs(candidates.centers[pi].y - curCenter.y)
        return if (dx <= candidates.tolerance && dy <= candidates.tolerance) dx + dy else null
    }

    private fun labelOf(
        node: ScreenNode,
        volatilePatterns: List<Regex>,
    ): String {
        val raw = node.text ?: node.desc ?: node.icon ?: node.hint ?: ""
        return stripVolatile(raw, volatilePatterns).trim()
    }

    private fun centerOf(node: ScreenNode): Point =
        node.at ?: Point(
            (node.bounds.l + node.bounds.r) / 2,
            (node.bounds.t + node.bounds.b) / 2,
        )

    private fun stateChanged(
        prev: ScreenNode,
        cur: ScreenNode,
    ): Boolean = prev.act != cur.act || prev.st != cur.st || prev.state != cur.state
}
