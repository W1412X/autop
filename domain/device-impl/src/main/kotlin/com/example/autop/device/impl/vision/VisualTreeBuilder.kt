/**
 * 伪树合成（docs/design/visual-screen-parsing §6.2；vp9 §3.1/§3.2）：
 * 原子吸收（[AtomicAbsorber]）→ 文字唯一归属（[TextOwnership]）→ 包含关系 → 阅读序 →
 * 先序编号；v2 多类直出带检测置信度与勾选三态，标签栏几何识别与 icon-in-item 去点在
 * 此层处理。与 PoC（poc/visual-parsing）行为一致。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.VisualTree
import com.example.autop.device.core.Containment
import kotlin.math.abs
import kotlin.math.max

/** 伪树合成器。 */
internal object VisualTreeBuilder {
    /**
     * 合成一帧伪树（root idx＝0，节点按先序编号排列）。vp9 §3.1/§3.2：先原子吸收再文字
     * 唯一归属；不变量（§3.4）[debug] 违反即抛，否则记 [warn]（注入判别，不读 BuildConfig）。
     * 无补扫/图标推理的兼容入口（完整流水见 [analyze]＋[build]）。
     */
    fun build(
        detections: List<VisualDetection>,
        ocrLines: List<VisualOcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        debug: Boolean = false,
        warn: (String) -> Unit = {},
    ): VisualTree {
        val analysis = analyze(detections, ocrLines)
        return build(FinalInput(analysis, ocrLines), screenWidth, screenHeight, debug, warn)
    }

    /** 分析中间态（vp9 §4.2 前半：骨架＋原子吸收＋文字归属）。 */
    internal data class Analysis(
        val absorbed: List<Absorbed>,
        val boxes: List<VisualDetection>,
        val ownership: Ownership,
        val kids: Map<Int, List<Int>>,
    )

    /** 定稿输入（vp9 §4.2 后半）：吸收结果＋最终行/归属＋图标显示名＋input 提示行。 */
    internal data class FinalInput(
        val analysis: Analysis,
        val lines: List<VisualOcrLine>,
        val ownership: Ownership = analysis.ownership,
        val iconLabels: Map<Int, String> = emptyMap(),
        val dupIou: Double = TextOwnership.DEFAULT_DUP_IOU,
        /** 框索引 → 判为提示语的行索引（vp9 §4.6，进 hint 不进 text）。 */
        val hintLines: Map<Int, List<Int>> = emptyMap(),
    )

    /** 一段（§4.2）：原子吸收＋文字唯一归属。 */
    fun analyze(
        detections: List<VisualDetection>,
        ocrLines: List<VisualOcrLine>,
        spanOverlap: Double = TextOwnership.DEFAULT_SPAN_OVERLAP,
    ): Analysis {
        val absorbed = AtomicAbsorber.absorb(detections)
        val boxes = absorbed.map { it.detection }
        val ownership = TextOwnership.assign(boxes, ocrLines, spanOverlap)
        val alive = boxes.indices.filterNot { it in ownership.drops }.toSet()
        return Analysis(absorbed, boxes, ownership, TextOwnership.childrenOf(boxes, alive))
    }

    /**
     * 二段（§4.2）：定稿伪树（补扫合并后的 [input] → 编号树）；不变量检查用 [input.dupIou]。
     */
    fun build(
        input: FinalInput,
        screenWidth: Int,
        screenHeight: Int,
        debug: Boolean = false,
        warn: (String) -> Unit = {},
    ): VisualTree {
        val nodes = assemble(input.analysis.absorbed, input.lines, input.ownership, input.iconLabels, input.hintLines)
        addTabBars(nodes, screenHeight)
        val ordered = readingOrder(nodes)
        val all = link(ordered, screenWidth, screenHeight)
        demoteIconInItem(all)
        TextOwnership.check(input.analysis.boxes, input.ownership, input.lines, input.dupIou, debug, warn)
        return VisualTree(rootIdx = 0, nodes = all.map { it.toSnapshot() })
    }

    /** 草稿节点（合成过程中的可变形态，定稿后转 [NodeSnapshot]）。 */
    private class PNode(
        var category: String,
        val bounds: Rect,
        var text: String? = null,
        val confidence: Double? = null,
        val checked: Boolean? = null,
        /** 吸收的 icon 子框（[AtomicAbsorber]，§5.2 裁图用）。 */
        val iconRegion: Rect? = null,
    ) {
        var idx: Int = 0
        var parent: Int? = null
        val children: MutableList<Int> = mutableListOf()
        var childIndex: Int = 0
        var drawingOrder: Int = 0
        var dropClickable: Boolean = false

        /** 图标分类显示名（§5.4，分类器接入后写入）。 */
        var iconLabel: String? = null

        /** input 提示语（vp9 §4.6，亮度差不足时文字改写到此）。 */
        var hint: String? = null
    }

    /** 检测框（吸收后）＋文字归属 → 草稿节点（合成 text 进树，vp9 §3.2 D/E；图标显示名、
     * input 提示语写入，§4.6 hint 行不进 text）。 */
    private fun assemble(
        absorbed: List<Absorbed>,
        lines: List<VisualOcrLine>,
        ownership: Ownership,
        iconLabels: Map<Int, String>,
        hintLines: Map<Int, List<Int>>,
    ): MutableList<PNode> {
        val nodes = mutableListOf<PNode>()
        val byBox = HashMap<Int, PNode>()
        for ((i, a) in absorbed.withIndex()) {
            if (i in ownership.drops) continue
            val node =
                PNode(
                    a.detection.category,
                    a.detection.bounds,
                    confidence = a.detection.confidence,
                    checked = a.detection.checked,
                    iconRegion = a.iconRegion,
                )
            node.iconLabel = iconLabels[i]
            nodes += node
            byBox[i] = node
        }
        for ((boxIdx, lineIdxs) in ownership.leafLines) {
            val hintIdx = hintLines[boxIdx].orEmpty().toSet()
            val node = byBox[boxIdx] ?: continue
            node.text = joinTexts(lineIdxs.filterNot { it in hintIdx }.map { lines[it].text })
            val hint = joinTexts(lineIdxs.filter { it in hintIdx }.map { lines[it].text })
            if (hint.isNotEmpty()) node.hint = hint
        }
        for (s in ownership.synthetic) {
            nodes += PNode(VisualCategory.TEXT, s.bounds, s.text, s.confidence)
        }
        return nodes
    }

    /** 文字拼接（vp9 §3.2 C）：阅读序、完全相同的串跳过。 */
    private fun joinTexts(texts: List<String>): String {
        val seen = mutableSetOf<String>()
        return texts.filter { seen.add(it) }.joinToString(" ")
    }

    /** 行带聚类（按 y 中心）＋行内按 x 排序，产出阅读序。 */
    private fun readingOrder(nodes: List<PNode>): List<PNode> {
        if (nodes.isEmpty()) return emptyList()
        val heights = nodes.map { it.bounds.height }.sorted()
        val band = max(TextOwnership.ROW_BAND_MIN, (TextOwnership.ROW_BAND_RATIO * heights[heights.size / 2]).toInt())
        val rows = mutableListOf<MutableList<PNode>>()
        for (node in nodes.sortedBy { it.bounds.centerY }) {
            val hit = rows.firstOrNull { abs(node.bounds.centerY - it.first().bounds.centerY) <= band }
            val row = hit ?: mutableListOf<PNode>().also { rows += it }
            row += node
        }
        return rows
            .sortedBy { row ->
                row.minOf { it.bounds.centerY }
            }.flatMap { row -> row.sortedBy { it.bounds.centerX } }
    }

    /** 包含关系建父子＋先序编号＋drawingOrder（面积小者靠上）；外加虚拟根（idx 0）。 */
    private fun link(
        nodes: List<PNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): List<PNode> {
        val root = PNode(VisualCategory.CONTAINER, Rect(0, 0, screenWidth, screenHeight))
        val parents = nodes.map { node -> parentIndexOf(node, nodes) }
        val rootKids = mutableListOf<Int>()
        val childLists = List(nodes.size) { mutableListOf<Int>() }
        nodes.indices.forEach { i ->
            val p = parents[i]
            if (p == null) rootKids += i else childLists[p] += i
        }
        var counter = 1

        fun assign(i: Int) {
            nodes[i].idx = counter
            counter += 1
            childLists[i].forEach { assign(it) }
        }
        rootKids.forEach { assign(it) }
        root.children += rootKids.map { nodes[it].idx }
        nodes.indices.forEach { i ->
            val p = parents[i]
            nodes[i].parent = p?.let { nodes[it].idx } ?: 0
            nodes[i].children += childLists[i].map { nodes[it].idx }
            val siblings = if (p == null) rootKids else childLists[p]
            nodes[i].childIndex = siblings.indexOf(i)
        }
        nodes.sortedByDescending { it.bounds.area }.forEachIndexed { order, n -> n.drawingOrder = order }
        return listOf(root) + nodes.sortedBy { it.idx }
    }

    /** 父＝包含自己的最小框（无则归根；规则与 llm 元素建树共用）。 */
    private fun parentIndexOf(
        node: PNode,
        nodes: List<PNode>,
    ): Int? = Containment.parentIndexOf(node.bounds, nodes.map { it.bounds })

    /** 草稿定稿：类别映射 className/flags/actions（vp8 §8.2）；状态写 stateDesc，置信度写 visualConf。 */
    private fun PNode.toSnapshot(): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            parent = parent,
            children = children,
            childIndex = childIndex,
            className = CategoryMapper.classNameOf(category),
            text = text,
            bounds = bounds,
            flags = flagsFor(),
            actions = CategoryMapper.actionsOf(category),
            drawingOrder = drawingOrder,
            stateDesc = CategoryMapper.stateDescOf(checked),
            visualConf = confidence?.toFloat(),
            iconLabel = iconLabel,
            hint = hint,
        )

    /** 定稿 flags：checked 加 CHECKED；icon-in-item 去 CLICKABLE（vp8 §8.2）。 */
    private fun PNode.flagsFor(): Int {
        val base = CategoryMapper.flagsOf(category, checked)
        return if (dropClickable) base and NodeFlags.CLICKABLE.inv() else base
    }

    /** 标签栏几何识别（vp8 §8.2，ADR-0008 决策 7）：命中项改判 tab，合成公共父节点。 */
    private fun addTabBars(
        nodes: MutableList<PNode>,
        screenHeight: Int,
    ) {
        val candidates = nodes.map { TabCandidate(it.category, it.bounds) }
        for (group in TabBarGrouper.group(candidates, screenHeight)) {
            val members = group.map { nodes[it] }
            members.forEach { it.category = VisualCategory.TAB }
            nodes += PNode(VisualCategory.TAB_GROUP, unionOf(members.map { it.bounds }))
        }
    }

    /** 并集框。 */
    private fun unionOf(boxes: List<Rect>): Rect =
        Rect(boxes.minOf { it.l }, boxes.minOf { it.t }, boxes.maxOf { it.r }, boxes.maxOf { it.b })

    /** §8.2：icon 位于 item 子树内时去掉 CLICKABLE（item 本身是交互单元，PoC demote 同口径）。 */
    private fun demoteIconInItem(nodes: List<PNode>) {
        val byIdx = nodes.associateBy { it.idx }
        for (node in nodes) {
            val isIcon = node.category == VisualCategory.ICON || node.category == VisualCategory.ICON_CLICKABLE
            if (isIcon && insideItem(node, byIdx)) node.dropClickable = true
        }
    }

    /** 祖先链上是否有 item 类节点。 */
    private fun insideItem(
        node: PNode,
        byIdx: Map<Int, PNode>,
    ): Boolean {
        var p = node.parent?.let { byIdx[it] }
        while (p != null) {
            if (p.category == VisualCategory.ITEM || p.category == VisualCategory.LIST_ITEM) return true
            p = p.parent?.let { byIdx[it] }
        }
        return false
    }
}
