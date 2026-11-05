/**
 * 文字唯一归属（vp9 §3.2，取代 attachText）：每条 OCR 行按 A 跨框行 → B 最小含心框 →
 * C 叶子写入 / D 容器合成 / E 根下合成 的顺序判定，第一个命中即停；同一容器内、同一行带
 * 且水平间距 < 行高的合成行合并。§3.4 不变量见 [check]。纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.abs
import kotlin.math.max

/** 合成 text 节点（§3.2 D：[parent]＝容器框索引；E：[parent] 为 null，根下）。 */
internal data class SyntheticText(
    val parent: Int?,
    val bounds: Rect,
    val text: String,
    val confidence: Double,
    /** 文字来源行实例（阅读序；§3.4 不变量 ①）。 */
    val lineIndexes: List<Int>,
)

/** A 步跨框行（§3.2 A：涉及叶子进补扫 E 类，vp9 §4.3；补扫未解决回落 B）。 */
internal data class SpanLine(
    val lineIndex: Int,
    val leaves: List<Int>,
)

/** OCR 行归属产物。 */
internal data class Ownership(
    /** 框索引 → 归属文字的行索引（阅读序拼接，完全相同的串跳过，§3.2 C）。 */
    val leafLines: Map<Int, List<Int>>,
    /** 合成 text 节点（阅读序）。 */
    val synthetic: List<SyntheticText>,
    /** A 步跨框行。 */
    val spans: List<SpanLine>,
    /** covered 丢弃的叶子 text 框索引（§3.2 尾）。 */
    val drops: List<Int>,
    /** 图标结果宿主（图标结果索引 → 框索引；§3.4 不变量 ②，步骤 7 填入）。 */
    val iconOwners: Map<Int, Int> = emptyMap(),
)

/** 文字归属器。 */
internal object TextOwnership {
    /** 阅读序行带：y 中心距 ≤ max(行高均值×[ROW_BAND_RATIO], [ROW_BAND_MIN]) 归同行。 */
    internal const val ROW_BAND_RATIO: Double = 0.6
    internal const val ROW_BAND_MIN: Int = 8

    /** A 步重叠阈值默认（`device.vision.rescan.spanOverlap` 默认值，步骤 8 接配置）。 */
    internal const val DEFAULT_SPAN_OVERLAP: Double = 0.3

    /** 不变量 ④ IoU 上限默认（`device.vision.rescan.dupIou` 默认值）。 */
    internal const val DEFAULT_DUP_IOU: Double = 0.5

    /** OCR 行归属到检测框的 IoU 阈值（超过即视为该框覆盖此文本，§3.2 尾 covered）。 */
    private const val OCR_ATTACH_IOU: Double = 0.3

    /**
     * 归属（§3.2）：[boxes] 为吸收后的检测框（扁平），叶子＝无子框。返回每行去处、
     * 合成节点、跨框行与 covered 丢弃框。
     */
    fun assign(
        boxes: List<VisualDetection>,
        lines: List<VisualOcrLine>,
        spanOverlap: Double,
    ): Ownership {
        val allKids = childrenOf(boxes, boxes.indices.toSet())
        val drops = coveredDrops(boxes, lines, allKids)
        val alive = boxes.indices.filterNot { it in drops }
        val kids = childrenOf(boxes, alive.toSet())
        val parents = parentsOf(boxes, alive.toSet())
        val leafLines = HashMap<Int, MutableList<Int>>()
        val cands = mutableListOf<SyntheticText>()
        val spans = mutableListOf<SpanLine>()
        for ((i, line) in lines.withIndex()) {
            spanLeaves(boxes, alive, kids, parents, line, spanOverlap).takeIf { it.size >= 2 }?.let {
                spans += SpanLine(i, it)
            }
            val winner = winnerOf(boxes, alive, line)
            when {
                winner == null -> cands += SyntheticText(null, line.bounds, line.text, line.confidence, listOf(i))
                kids.getValue(winner).isEmpty() -> leafLines.getOrPut(winner) { mutableListOf() } += i
                else -> cands += SyntheticText(winner, line.bounds, line.text, line.confidence, listOf(i))
            }
        }
        return Ownership(leafLines.mapValues { it.value.toList() }, mergeByBand(cands), spans, drops.sorted())
    }

    /**
     * §3.4 不变量：①每条最终文字行实例恰在一个叶子的 text 中；②每个图标结果（
     * [Ownership.iconOwners]）恰在一个叶子的 icon 中；③容器 text/icon 为空；
     * ④任意两叶子的文字来源行 IoU ≤ [dupIou]。
     * [debug] 违反即抛（check() 失败）；否则记 `warn`（`[vision] ownership_violation`）。
     */
    fun check(
        boxes: List<VisualDetection>,
        ownership: Ownership,
        lines: List<VisualOcrLine>,
        dupIou: Double,
        debug: Boolean,
        warn: (String) -> Unit,
    ) {
        val kids = childrenOf(boxes, boxes.indices.toSet())
        val violations = mutableListOf<String>()
        checkLineInstances(ownership, lines.size, violations)
        checkHosts(kids, ownership, violations)
        checkDupLines(ownership, lines, dupIou, violations)
        if (violations.isEmpty()) return
        val msg = "[vision] ownership_violation: " + violations.joinToString("; ")
        if (debug) error(msg) else warn(msg)
    }

    /** ①：行实例计数恰为 1（leafLines＋synthetic）。 */
    private fun checkLineInstances(
        ownership: Ownership,
        lineCount: Int,
        violations: MutableList<String>,
    ) {
        val counts = IntArray(lineCount)
        for (lines in ownership.leafLines.values) for (i in lines) counts[i] += 1
        for (s in ownership.synthetic) for (i in s.lineIndexes) counts[i] += 1
        counts.forEachIndexed { i, n -> if (n != 1) violations += "line $i 归属 $n 处" }
    }

    /** ②③：图标结果与文字宿主都必须是叶子（容器 text/icon 为空）。 */
    private fun checkHosts(
        kids: Map<Int, List<Int>>,
        ownership: Ownership,
        violations: MutableList<String>,
    ) {
        for (box in ownership.leafLines.keys) {
            if (kids[box]?.isNotEmpty() == true) violations += "container $box 持有 text"
        }
        for ((icon, box) in ownership.iconOwners) {
            if (kids[box]?.isNotEmpty() == true) violations += "icon $icon 归属 container $box"
        }
    }

    /** ④：任意两叶子的文字来源行 IoU ≤ [dupIou]（合成节点同为叶子）。 */
    private fun checkDupLines(
        ownership: Ownership,
        lines: List<VisualOcrLine>,
        dupIou: Double,
        violations: MutableList<String>,
    ) {
        val leaves =
            ownership.leafLines.values +
                ownership.synthetic.map { it.lineIndexes }
        val all = leaves.flatten()
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                if (lines[all[i]].bounds.iou(lines[all[j]].bounds) > dupIou) {
                    violations += "line ${all[i]} 与 ${all[j]} 重复（IoU > $dupIou）"
                }
            }
        }
    }

    /** covered：被 OCR 行覆盖的叶子 text 框丢弃（只对叶子生效，§3.2 尾）。 */
    private fun coveredDrops(
        boxes: List<VisualDetection>,
        lines: List<VisualOcrLine>,
        kids: Map<Int, List<Int>>,
    ): List<Int> =
        boxes.indices
            .filter { boxes[it].category in VisualCategory.TEXT_KINDS && kids.getValue(it).isEmpty() }
            .filter { det ->
                lines.any { it.bounds.iou(boxes[det].bounds) > OCR_ATTACH_IOU || it.bounds.centerIn(boxes[det].bounds) }
            }

    /** A 步：行与 ≥2 个兄弟叶子各重叠 ≥ [spanOverlap]（按行面积）→ 这些叶子。 */
    private fun spanLeaves(
        boxes: List<VisualDetection>,
        alive: List<Int>,
        kids: Map<Int, List<Int>>,
        parents: Map<Int, Int?>,
        line: VisualOcrLine,
        spanOverlap: Double,
    ): List<Int> {
        val hit =
            alive.filter { i ->
                kids.getValue(i).isEmpty() && overlapRatio(line.bounds, boxes[i].bounds) >= spanOverlap
            }
        if (hit.size < 2) return emptyList()
        return hit
            .groupBy { parents[it] }
            .values
            .maxByOrNull { it.size }
            ?.takeIf { it.size >= 2 }
            .orEmpty()
    }

    /** B 步：中心落入若干框 → 面积最小者；面积相同按 §3.1 优先级（vp8 §3.5）。 */
    private fun winnerOf(
        boxes: List<VisualDetection>,
        alive: List<Int>,
        line: VisualOcrLine,
    ): Int? =
        alive
            .filter { line.bounds.centerIn(boxes[it].bounds) }
            .minWithOrNull(
                compareBy({ boxes[it].bounds.area }, { -AtomicAbsorber.priorityOf(boxes[it].category) }),
            )

    /** 交叠面积占 [line] 面积比例。 */
    private fun overlapRatio(
        line: Rect,
        box: Rect,
    ): Double {
        val ix = max(0, minOf(line.r, box.r) - max(line.l, box.l))
        val iy = max(0, minOf(line.b, box.b) - max(line.t, box.t))
        return if (line.area <= 0) 0.0 else ix.toLong() * iy / line.area.toDouble()
    }

    /** 子框索引（落入且面积更小；补扫规划共用）。 */
    internal fun childrenOf(
        boxes: List<VisualDetection>,
        alive: Set<Int>,
    ): Map<Int, List<Int>> =
        alive.associateWith { i ->
            alive.filter { j ->
                j != i &&
                    boxes[i].bounds.area > boxes[j].bounds.area &&
                    boxes[i].bounds.contains(boxes[j].bounds)
            }
        }

    /** 父＝包含自己的最小框（无则根下）。 */
    private fun parentsOf(
        boxes: List<VisualDetection>,
        alive: Set<Int>,
    ): Map<Int, Int?> =
        alive.associateWith { i ->
            val candidates =
                alive.filter { j ->
                    j != i &&
                        boxes[j].bounds.area > boxes[i].bounds.area &&
                        boxes[j].bounds.contains(boxes[i].bounds)
                }
            candidates.minByOrNull { boxes[it].bounds.area }
        }

    /** 同一容器内、同一行带且水平间距 < 行高的合成候选合并（§3.2）。 */
    private fun mergeByBand(cands: List<SyntheticText>): List<SyntheticText> {
        val groups = mutableListOf<MutableList<SyntheticText>>()
        for (c in cands) {
            val hit = groups.firstOrNull { g -> g.any { sameBand(it, c) } }
            (hit ?: mutableListOf<SyntheticText>().also { groups += it }) += c
        }
        return groups.map { mergeGroup(it) }
    }

    /** 同行带：同容器、y 中心距 ≤ band、水平间距 < 行高（均值口径）。 */
    private fun sameBand(
        a: SyntheticText,
        b: SyntheticText,
    ): Boolean {
        val height = (a.bounds.height + b.bounds.height) / 2
        val band = max(ROW_BAND_MIN, (ROW_BAND_RATIO * height).toInt())
        val gap = max(0, max(a.bounds.l, b.bounds.l) - minOf(a.bounds.r, b.bounds.r))
        return a.parent == b.parent &&
            abs(a.bounds.centerY - b.bounds.centerY) <= band &&
            gap < height
    }

    /** 合并候选：并集框、串去重拼接（完全相同的串跳过）、conf 取组内最大、来源行保序。 */
    private fun mergeGroup(group: List<SyntheticText>): SyntheticText {
        val seen = mutableSetOf<String>()
        val text =
            group
                .mapNotNull { if (seen.add(it.text)) it.text else null }
                .joinToString(" ")
        val union =
            Rect(
                group.minOf { it.bounds.l },
                group.minOf { it.bounds.t },
                group.maxOf { it.bounds.r },
                group.maxOf { it.bounds.b },
            )
        return SyntheticText(
            parent = group.first().parent,
            bounds = union,
            text = text,
            confidence = group.maxOf { it.confidence },
            lineIndexes = group.flatMap { it.lineIndexes },
        )
    }
}
