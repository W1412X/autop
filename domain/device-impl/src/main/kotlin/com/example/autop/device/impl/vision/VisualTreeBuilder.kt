/**
 * 伪树合成（docs/design/visual-screen-parsing §6.2）：原子吸收（[AtomicAbsorber]）→ 包含关系
 * 建树 → 阅读序 → 先序编号。叶子内容（OCR 文本/图标显示名/提示语）由编排层逐叶采集后直挂，
 * 无文本归属/补扫环节；v2 多类直出带检测置信度与勾选三态，标签栏几何识别与 icon-in-item
 * 去点在此层处理。
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
    /** 分析中间态：原子吸收＋包含关系（叶子＝无子框的框）。 */
    internal data class Analysis(
        val absorbed: List<Absorbed>,
        val boxes: List<VisualDetection>,
        val kids: Map<Int, List<Int>>,
    ) {
        /** 叶子框索引（吸收后、无子框）。 */
        fun leaves(): List<Int> = boxes.indices.filter { kids[it].isNullOrEmpty() }
    }

    /** 叶子采集内容（编排层逐叶产出，直挂节点）。 */
    internal data class LeafInput(
        /** OCR 文本（空文本传 null）。 */
        val text: String? = null,
        /** input 提示语（非 null 时文字进 hint 不进 text，vp9 §4.6）。 */
        val hint: String? = null,
        /** 图标分类显示名（拒识为 null）。 */
        val iconLabel: String? = null,
    )

    /** 一段：吸收＋包含关系（检测→叶子的唯一依据，与 OCR 无关）。 */
    fun analyze(detections: List<VisualDetection>): Analysis {
        val absorbed = AtomicAbsorber.absorb(detections)
        val boxes = absorbed.map { it.detection }
        return Analysis(absorbed, boxes, childrenOf(boxes))
    }

    /**
     * 二段：定稿伪树（叶子内容 [leaves] 按框索引直挂 → 编号树）。
     * [debug] 违反树形约定即抛，否则记 [warn]。
     */
    fun build(
        analysis: Analysis,
        leaves: Map<Int, LeafInput>,
        screenWidth: Int,
        screenHeight: Int,
        debug: Boolean = false,
        warn: (String) -> Unit = {},
    ): VisualTree {
        val nodes = assemble(analysis, leaves)
        addTabBars(nodes, screenHeight)
        val ordered = readingOrder(nodes)
        val all = link(ordered, screenWidth, screenHeight)
        demoteIconInItem(all)
        checkTree(all, debug, warn)
        return VisualTree(rootIdx = 0, nodes = all.map { it.toSnapshot() })
    }

    /** 草稿节点（合成过程中的可变形态，定稿后转 [NodeSnapshot]）。 */
    private class PNode(
        var category: String,
        val bounds: Rect,
        var text: String? = null,
        val confidence: Double? = null,
        val checked: Boolean? = null,
        /** 吸收的 icon 子框（[AtomicAbsorber]，裁图用）。 */
        val iconRegion: Rect? = null,
    ) {
        var idx: Int = 0
        var parent: Int? = null
        val children: MutableList<Int> = mutableListOf()
        var childIndex: Int = 0
        var drawingOrder: Int = 0
        var dropClickable: Boolean = false

        /** 图标分类显示名（分类器接入后写入）。 */
        var iconLabel: String? = null

        /** input 提示语（vp9 §4.6，亮度差不足时文字改写到此）。 */
        var hint: String? = null
    }

    /** 检测框（吸收后）＋叶子采集内容 → 草稿节点。 */
    private fun assemble(
        analysis: Analysis,
        leaves: Map<Int, LeafInput>,
    ): MutableList<PNode> {
        val nodes = mutableListOf<PNode>()
        for ((i, a) in analysis.absorbed.withIndex()) {
            val leaf = leaves[i]
            val node =
                PNode(
                    a.detection.category,
                    a.detection.bounds,
                    text = leaf?.text,
                    confidence = a.detection.confidence,
                    checked = a.detection.checked,
                    iconRegion = a.iconRegion,
                )
            node.iconLabel = leaf?.iconLabel
            node.hint = leaf?.hint
            nodes += node
        }
        return nodes
    }

    /** 行带聚类（按 y 中心）＋行内按 x 排序，产出阅读序。 */
    private fun readingOrder(nodes: List<PNode>): List<PNode> {
        if (nodes.isEmpty()) return emptyList()
        val heights = nodes.map { it.bounds.height }.sorted()
        val band = max(ROW_BAND_MIN, (ROW_BAND_RATIO * heights[heights.size / 2]).toInt())
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

    /** 包含关系：框索引 → 直接子框索引（纯几何，叶子＝无子框）。 */
    private fun childrenOf(boxes: List<VisualDetection>): Map<Int, List<Int>> {
        val kids = boxes.indices.associateWith { mutableListOf<Int>() }
        for ((i, box) in boxes.withIndex()) {
            val p = Containment.parentIndexOf(box.bounds, boxes.map { it.bounds })
            if (p != null && p != i) kids.getValue(p) += i
        }
        return kids
    }

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

    /** 树形约定：容器不持文字、节点索引连续（debug 抛出，release 记 warn）。 */
    private fun checkTree(
        nodes: List<PNode>,
        debug: Boolean,
        warn: (String) -> Unit,
    ) {
        val violations = mutableListOf<String>()
        for (node in nodes) {
            if (node.children.isNotEmpty() && node.text != null) {
                violations += "container ${node.idx} 持有 text"
            }
        }
        nodes.forEachIndexed { i, n -> if (n.idx != i) violations += "idx 序断裂：$i 处为 ${n.idx}" }
        if (violations.isEmpty()) return
        val msg = "[vision] tree_violation: " + violations.joinToString("; ")
        if (debug) error(msg) else warn(msg)
    }

    /** 行带高度容差（阅读序聚类）。 */
    private const val ROW_BAND_MIN: Int = 8
    private const val ROW_BAND_RATIO: Double = 0.6
}
