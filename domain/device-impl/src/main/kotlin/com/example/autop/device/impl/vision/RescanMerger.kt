/**
 * 补扫合并（vp9 §4.5，纯 Kotlin 可单测）：①行中心不在原框内丢弃；②与已归属行重叠
 * （IoU > dupIou 或中心互相落入）时，原行主人是本框且新行置信度更高 → 替换，否则丢弃；
 * ③剩余行归本框（叶子写自己的 text，D 类 item 按 §3.2 D 建合成子节点）；④E 类涉及叶子
 * 都补到字则丢弃原跨框行，否则原行按 §3.2 B 回落（保持原归属）。
 */
package com.example.autop.device.impl.vision

/** 合并产物：最终行列表（索引已重排）＋更新后的归属。 */
internal data class RescanMerge(
    val lines: List<VisualOcrLine>,
    val ownership: Ownership,
)

/** 补扫合并器。 */
internal object RescanMerger {
    /** 合并输入（vp9 §4.5）。 */
    internal data class MergeInput(
        val candidates: List<RescanCandidate>,
        val rescans: Map<Int, List<VisualOcrLine>>,
        val kids: Map<Int, List<Int>>,
    )

    /**
     * 合并补扫行进归属（vp9 §4.5）：[input.rescans] 为候选框索引 → 补扫行；[boxes] 为
     * 吸收后检测框；[dupIou] 为重叠判定阈值。
     */
    fun merge(
        boxes: List<VisualDetection>,
        lines: List<VisualOcrLine>,
        ownership: Ownership,
        input: MergeInput,
        dupIou: Double,
    ): RescanMerge {
        val workLines = lines.toMutableList()
        val leafLines = HashMap<Int, MutableList<Int>>()
        for ((k, v) in ownership.leafLines) leafLines[k] = v.toMutableList()
        val synthetic = ownership.synthetic.toMutableList()
        val dropped = mutableSetOf<Int>()
        val spanLines = ownership.spans.map { it.lineIndex }.toSet()
        for (c in input.candidates) {
            for (nl in input.rescans[c.boxIndex].orEmpty()) {
                val host = c.boxIndex
                if (!nl.bounds.centerIn(boxes[host].bounds)) continue
                // E 类原跨框行不算②的冲突对象（④ 统一收口），避免补扫行被顶替后随原行丢弃。
                val old = findOverlap(workLines, leafLines, synthetic, nl, dupIou, spanLines)
                when {
                    old != null && hostOf(old, leafLines, synthetic) != host -> Unit
                    old != null -> replaceIfBetter(workLines, old, nl, synthetic)
                    else -> attach(workLines, leafLines, synthetic, input.kids, host, nl)
                }
            }
        }
        dropResolvedSpans(ownership, leafLines, dropped)
        return remap(workLines, ownership, leafLines, synthetic, dropped)
    }

    /** 找与新行重叠的已归属行索引（IoU > [dupIou] 或中心互相落入；[spanLines] 不参与），无则 null。 */
    private fun findOverlap(
        workLines: List<VisualOcrLine>,
        leafLines: Map<Int, MutableList<Int>>,
        synthetic: List<SyntheticText>,
        nl: VisualOcrLine,
        dupIou: Double,
        spanLines: Set<Int>,
    ): Int? {
        val owned = leafLines.values.flatten() + synthetic.flatMap { it.lineIndexes }
        val candidates =
            owned
                .filter { it !in spanLines }
                .mapNotNull { i -> workLines.getOrNull(i)?.let { it to i } }
        val hit =
            candidates.firstOrNull { (old, _) ->
                old.bounds.iou(nl.bounds) > dupIou ||
                    old.bounds.centerIn(nl.bounds) ||
                    nl.bounds.centerIn(old.bounds)
            }
        return hit?.second
    }

    /** 行主人（叶子框索引或合成节点虚拟键）。 */
    private fun hostOf(
        lineIndex: Int,
        leafLines: Map<Int, MutableList<Int>>,
        synthetic: List<SyntheticText>,
    ): Int? {
        val leaf = leafLines.entries.firstOrNull { lineIndex in it.value }?.key
        val synth = synthetic.indexOfFirst { lineIndex in it.lineIndexes }
        return leaf ?: synth.takeIf { it >= 0 }?.let { SYNTHETIC_BASE - it }
    }

    /** ②替换：新行置信度更高时原地顶替（行索引不变），合成节点文本同步。 */
    private fun replaceIfBetter(
        workLines: MutableList<VisualOcrLine>,
        old: Int,
        nl: VisualOcrLine,
        synthetic: MutableList<SyntheticText>,
    ) {
        if (nl.confidence <= workLines[old].confidence) return
        workLines[old] = nl
        for (i in synthetic.indices) {
            val s = synthetic[i]
            if (old in s.lineIndexes) {
                val text = s.lineIndexes.mapNotNull { workLines.getOrNull(it)?.text }.joinToString(" ")
                synthetic[i] = s.copy(text = text)
            }
        }
    }

    /** ③归本框：叶子写自己的 text；D 类 item（容器）按 §3.2 D 建合成子节点。 */
    private fun attach(
        workLines: MutableList<VisualOcrLine>,
        leafLines: MutableMap<Int, MutableList<Int>>,
        synthetic: MutableList<SyntheticText>,
        kids: Map<Int, List<Int>>,
        host: Int,
        nl: VisualOcrLine,
    ) {
        workLines += nl
        val idx = workLines.lastIndex
        if (kids[host].isNullOrEmpty()) {
            leafLines.getOrPut(host) { mutableListOf() } += idx
        } else {
            synthetic += SyntheticText(host, nl.bounds, nl.text, nl.confidence, listOf(idx))
        }
    }

    /** ④E 类：涉及叶子都补到字 → 丢弃原跨框行。 */
    private fun dropResolvedSpans(
        ownership: Ownership,
        leafLines: Map<Int, MutableList<Int>>,
        dropped: MutableSet<Int>,
    ) {
        for (span in ownership.spans) {
            val allFilled = span.leaves.all { !leafLines[it].isNullOrEmpty() }
            if (allFilled) dropped += span.lineIndex
        }
    }

    /** 索引重排：剔除丢弃行，重建 lines 与 Ownership（含 spans）。 */
    private fun remap(
        workLines: List<VisualOcrLine>,
        ownership: Ownership,
        leafLines: Map<Int, MutableList<Int>>,
        synthetic: List<SyntheticText>,
        dropped: Set<Int>,
    ): RescanMerge {
        val keep = workLines.indices.filterNot { it in dropped }
        val remap = HashMap<Int, Int>()
        keep.forEachIndexed { new, old -> remap[old] = new }
        val newLines = keep.map { workLines[it] }
        val mappedLeaf =
            leafLines.mapValues { (_, idxs) ->
                idxs.filter { it in remap }.map { remap.getValue(it) }
            }
        val newLeaf = mappedLeaf.filterValues { it.isNotEmpty() }
        val newSynthetic =
            synthetic.mapNotNull { s ->
                val idxs = s.lineIndexes.filter { it in remap }.map { remap.getValue(it) }
                if (idxs.isEmpty()) null else s.copy(lineIndexes = idxs)
            }
        val newSpans =
            ownership.spans.filter { it.lineIndex in remap }.map { SpanLine(remap.getValue(it.lineIndex), it.leaves) }
        return RescanMerge(newLines, Ownership(newLeaf, newSynthetic, newSpans, ownership.drops, ownership.iconOwners))
    }

    /** 合成节点虚拟宿主键基值（hostOf 反查用）。 */
    private const val SYNTHETIC_BASE: Int = 1_000_000
}
