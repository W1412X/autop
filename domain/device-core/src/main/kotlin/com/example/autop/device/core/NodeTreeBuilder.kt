/**
 * 节点树处理（docs/tech/05 §3.2 步骤 2–10、docs/design/observation-modes-coordinate-actions §3.3）：
 * 可见性、分类、标签合并、扁平化、列表折叠，输出统一模型所需的扁平行。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.WindowSnapshot

/** 折叠伪行的 idx 标记（不参与差量与指纹）。 */
internal const val FOLD_ROW_IDX: Int = -1

/**
 * 步骤 2：节点可见 = VISIBLE 位 && 面积 > 0 && 与窗口 bounds 相交 &&
 * （隐藏装饰区时）与内容区相交；不可见连同子树丢弃。
 */
public fun nodeVisibleIn(
    node: NodeSnapshot,
    window: WindowSnapshot,
    area: Rect?,
): Boolean {
    val bounds = node.bounds
    return node.flags and NodeFlags.VISIBLE != 0 &&
        bounds.area > 0 &&
        intersects(bounds, window.bounds) &&
        (area == null || intersects(bounds, area))
}

private fun intersects(
    a: Rect,
    b: Rect,
): Boolean = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b

/** 把一个窗口的节点树转成输出行。 */
internal class NodeTreeBuilder(
    private val config: SerializerConfig,
    private val rules: SerializerRules,
    private val volatilePatterns: List<Regex>,
    private val geometry: TapPointResolver.Geometry,
) {
    private data class Vis(
        val node: NodeSnapshot,
        val parent: Int?,
        val category: NodeCategory,
    )

    private data class Entry(
        val node: NodeSnapshot?,
        val category: NodeCategory,
        val label: NodeLabel,
        val depth: Int,
    )

    fun build(
        window: WindowSnapshot,
        index: SnapshotIndex,
        area: Rect? = null,
        screen: PixelSize = PixelSize(0, 0),
        mode: ObserveMode = ObserveMode.A11Y,
    ): List<KeptNode> {
        val visual = mode != ObserveMode.A11Y
        val vis = mutableListOf<Vis>()
        val rootIdx = window.rootIdx ?: return emptyList()

        fun collect(
            idx: Int,
            parent: Int?,
        ) {
            val node = index.node(idx) ?: return
            if (!nodeVisibleIn(node, window, area)) return
            vis += Vis(node, parent, NodeText.categoryOf(node))
            for (child in node.children) collect(child, idx)
        }
        collect(rootIdx, null)
        val consumed = HashSet<Int>()
        // 视觉模式不合并后代标签、不 consume（避免重复制造归属）。
        val labels = if (visual) emptyMap() else mergeLabels(vis, consumed)
        val entries = flatten(vis, consumed, labels, visual)
        return foldLists(entries).map { toKeptNode(it, window, index, area, screen, mode) }
    }

    /**
     * 步骤 4：actionable 节点（CLICKABLE/LONG_CLICKABLE/EDITABLE/CHECKABLE 之一，不看 ENABLED；
     * 只有 SCROLLABLE 的不收集）合并后代标签。先序遍历，遇 interactive 后代整棵子树跳过；
     * 与自身或已收集标签重复（trim 比较）的跳过但仍标记 consumed。
     */
    private fun mergeLabels(
        vis: List<Vis>,
        consumed: MutableSet<Int>,
    ): Map<Int, NodeLabel> {
        val childrenOf = vis.groupBy { it.parent }
        val merged = HashMap<Int, NodeLabel>()
        for (v in vis) {
            val actionable = v.node.flags and ACTIONABLE_ANY != 0 && v.node.flags and NodeFlags.PASSWORD == 0
            if (actionable) {
                val own = ownLabelOf(v.node)
                val extras = mutableListOf<String>()
                collectDescendants(v.node.idx, childrenOf, own, extras, consumed)
                merged[v.node.idx] = NodeText.labelOf(v.node, extras, rules, config)
            }
        }
        return merged
    }

    /** 先序收集非 interactive 后代的文本标签（最多 [SerializerConfig.maxMergedLabels] 个），标记 consumed。 */
    private fun collectDescendants(
        parentIdx: Int,
        childrenOf: Map<Int?, List<Vis>>,
        own: String?,
        out: MutableList<String>,
        consumed: MutableSet<Int>,
    ) {
        for (child in childrenOf[parentIdx].orEmpty()) {
            if (out.size >= config.maxMergedLabels) return
            if (child.node.flags and NodeText.INTERACTIVE_ANY != 0) continue
            val label = ownLabelOf(child.node)
            if (label != null) {
                val dup = label.trim() == own?.trim() || out.any { it.trim() == label.trim() }
                if (!dup && out.size < config.maxMergedLabels) out += label
                consumed += child.node.idx
            }
            collectDescendants(child.node.idx, childrenOf, own, out, consumed)
        }
    }

    /** 收集口径的自身标签：text → contentDesc。 */
    private fun ownLabelOf(node: NodeSnapshot): String? =
        node.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDesc?.trim()?.takeIf { it.isNotEmpty() }

    /** 步骤 5：容器与 consumed 不输出，子节点提升到最近输出祖先下；视觉模式保留 keepContainerTags 的容器。 */
    private fun flatten(
        vis: List<Vis>,
        consumed: Set<Int>,
        labels: Map<Int, NodeLabel>,
        visual: Boolean,
    ): List<Entry> {
        val byParent = vis.groupBy { it.parent }
        val out = mutableListOf<Entry>()

        fun walk(
            parent: Int?,
            depth: Int,
        ) {
            for (v in byParent[parent].orEmpty()) {
                val output = outputs(v, consumed, visual)
                if (output) {
                    out += Entry(v.node, v.category, labelOf(v.node, labels, visual), depth)
                }
                walk(v.node.idx, if (output) depth + 1 else depth)
            }
        }
        walk(null, 0)
        return out
    }

    /** 输出判定：非 CONTAINER 行输出；视觉模式下 keepContainerTags 的 CONTAINER 也输出。 */
    private fun outputs(
        v: Vis,
        consumed: Set<Int>,
        visual: Boolean,
    ): Boolean =
        when {
            v.node.idx in consumed -> false
            v.category != NodeCategory.CONTAINER -> true
            else -> visual && keptContainer(v.node)
        }

    /** 视觉模式：className 映射出的标签名命中 rules `keepContainerTags`。 */
    private fun keptContainer(node: NodeSnapshot): Boolean {
        val tag = NodeText.mappedTag(node.className, rules) ?: return false
        return tag in rules.keepContainerTags
    }

    /** 行标签：视觉取 node.text / node.hint（无兜底链）；无障碍走合并/兜底链。 */
    private fun labelOf(
        node: NodeSnapshot,
        labels: Map<Int, NodeLabel>,
        visual: Boolean,
    ): NodeLabel =
        if (visual) {
            when {
                !node.text.isNullOrEmpty() -> NodeLabel(node.text.orEmpty(), LabelKind.TEXT)
                !node.hint.isNullOrEmpty() -> NodeLabel(node.hint.orEmpty(), LabelKind.HINT)
                else -> NodeLabel("", LabelKind.EMPTY)
            }
        } else {
            labels[node.idx] ?: NodeText.labelOf(node, emptyList(), rules, config)
        }

    /** 步骤 6：可滚动节点的直接输出子节点超限时折叠，追加“…还有 n 项”。 */
    private fun foldLists(entries: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>(entries.size)
        var i = 0
        while (i < entries.size) {
            val e = entries[i]
            out += e
            i++
            val childEnd = (i until entries.size).firstOrNull { entries[it].depth <= e.depth } ?: entries.size
            val direct = (i until childEnd).filter { entries[it].depth == e.depth + 1 }
            val scrollable = e.node != null && e.node.flags and NodeFlags.SCROLLABLE != 0
            if (!scrollable || config.maxListItems <= 0 || direct.size <= config.maxListItems) {
                out += entries.subList(i, childEnd)
                i = childEnd
                continue
            }
            val cut = direct[config.maxListItems]
            out += entries.subList(i, cut)
            val rows = e.node.collectionRows?.let { it * maxOf(1, e.node.collectionCols ?: 1) } ?: direct.size
            val foldLabel = "…还有 ${maxOf(1, rows - config.maxListItems)} 项，可滚动"
            out += Entry(null, NodeCategory.CONTAINER, NodeLabel(foldLabel, LabelKind.TEXT), e.depth + 1)
            i = childEnd
        }
        return out
    }

    private fun toKeptNode(
        entry: Entry,
        window: WindowSnapshot,
        index: SnapshotIndex,
        area: Rect?,
        screen: PixelSize,
        mode: ObserveMode,
    ): KeptNode {
        val node = entry.node
        if (node == null) {
            // 折叠伪行：前导“…”去掉后作为父列表节点的 more。
            return KeptNode(
                idx = FOLD_ROW_IDX,
                depth = entry.depth,
                key = "",
                windowId = window.windowId,
                tag = "",
                more = entry.label.text.removePrefix("…"),
            )
        }
        val visual = mode != ObserveMode.A11Y
        val tag = NodeText.tagOf(node, entry.category, entry.label.text, rules)
        val key = stableNodeKey(window.packageName, node, tag, entry.label.text, index, volatilePatterns)
        val bounds = normalizedBox(node.bounds, area, screen)
        val act = actOf(node, visual)
        return KeptNode(
            idx = node.idx,
            depth = entry.depth,
            key = key,
            windowId = window.windowId,
            tag = tag,
            text = textOf(entry.label),
            icon = node.iconLabel?.takeIf { visual },
            desc = descOf(node, entry.label, visual),
            hint = hintOf(node, entry.label, visual),
            state = stateOf(node, visual),
            cls = node.className?.substringAfterLast('.')?.takeIf { !visual && it.isNotEmpty() },
            vid = ViewIdHumanizer.shortName(node.viewId, rules)?.takeIf { !visual },
            act = act,
            st = stOf(node, visual),
            bounds = bounds,
            at = atOf(node, bounds, act, visual, area, screen),
            labelKind = entry.label.kind,
        )
    }

    /** 归一化外接框（内容区基准 0–1000，跨界钳制）；无 bounds 或尺寸非法返回 null。 */
    private fun normalizedBox(
        bounds: Rect?,
        area: Rect?,
        screen: PixelSize,
    ): Rect? {
        val width = area?.width ?: screen.width
        val height = area?.height ?: screen.height
        if (bounds == null || width <= 0 || height <= 0) return null
        val left = bounds.l - (area?.l ?: 0)
        val top = bounds.t - (area?.t ?: 0)
        val right = bounds.r - (area?.l ?: 0)
        val bottom = bounds.b - (area?.t ?: 0)
        return Rect(
            left.coerceIn(0, width) * COORD_SCALE / width,
            top.coerceIn(0, height) * COORD_SCALE / height,
            right.coerceIn(0, width) * COORD_SCALE / width,
            bottom.coerceIn(0, height) * COORD_SCALE / height,
        )
    }

    /** 归一化点（内容区基准 0–1000，钳制）。 */
    private fun normalizePoint(
        point: Point,
        area: Rect?,
        screen: PixelSize,
    ): Point {
        val width = (area?.width ?: screen.width).coerceAtLeast(1)
        val height = (area?.height ?: screen.height).coerceAtLeast(1)
        val x = point.x - (area?.l ?: 0)
        val y = point.y - (area?.t ?: 0)
        return Point(
            x.coerceIn(0, width) * COORD_SCALE / width,
            y.coerceIn(0, height) * COORD_SCALE / height,
        )
    }

    private fun actOf(
        node: NodeSnapshot,
        visual: Boolean,
    ): List<String> =
        buildList {
            if (node.flags and NodeFlags.CLICKABLE != 0) add(ScreenJsonWire.ACT_CLICK)
            if (!visual && node.flags and NodeFlags.LONG_CLICKABLE != 0) add(ScreenJsonWire.ACT_LONG_PRESS)
            if (node.flags and NodeFlags.EDITABLE != 0) add(ScreenJsonWire.ACT_EDIT)
            if (node.flags and NodeFlags.SCROLLABLE != 0) add(ScreenJsonWire.ACT_SCROLL)
        }

    private fun stOf(
        node: NodeSnapshot,
        visual: Boolean,
    ): List<String> =
        buildList {
            if (node.flags and NodeFlags.CHECKABLE != 0) {
                add(
                    if (node.flags and NodeFlags.CHECKED !=
                        0
                    ) {
                        ScreenJsonWire.ST_CHECKED
                    } else {
                        ScreenJsonWire.ST_UNCHECKED
                    },
                )
            }
            if (node.flags and NodeFlags.SELECTED != 0) add(ScreenJsonWire.ST_SELECTED)
            if (node.flags and NodeFlags.ENABLED == 0) add(ScreenJsonWire.ST_DISABLED)
            if (!visual && node.flags and NodeFlags.PASSWORD != 0) add(ScreenJsonWire.ST_PASSWORD)
            if (!visual && node.flags and NodeFlags.HEADING != 0) add(ScreenJsonWire.ST_HEADING)
            if (visual) {
                val conf = node.visualConf
                if (conf != null && conf < config.uncertainConf) add(ScreenJsonWire.ST_UNCERTAIN)
            }
        }

    private fun textOf(label: NodeLabel): String = if (label.kind == LabelKind.TEXT) label.text.trim() else ""

    private fun hintOf(
        node: NodeSnapshot,
        label: NodeLabel,
        visual: Boolean,
    ): String =
        when {
            !visual -> node.hint.orEmpty().trim()
            label.kind == LabelKind.HINT -> NodeText.normalize(label.text).trim()
            else -> ""
        }

    /** 无障碍 desc：contentDesc 原文，与 text 首段相同时省略；视觉模式没有 desc。 */
    private fun descOf(
        node: NodeSnapshot,
        label: NodeLabel,
        visual: Boolean,
    ): String {
        if (visual) return ""
        val desc = node.contentDesc.orEmpty().trim()
        val textHead = label.text.substringBefore(LABEL_SEP).trim()
        return if (desc.isNotEmpty() && !(label.kind == LabelKind.TEXT && textHead == desc)) desc else ""
    }

    /** state：a11y 原文；visual 用 on/off（视觉状态分类内部文案映射）。 */
    private fun stateOf(
        node: NodeSnapshot,
        visual: Boolean,
    ): String {
        val raw = node.stateDesc.orEmpty().trim()
        if (!visual) return NodeText.truncate(raw, config)
        return when (raw) {
            VISUAL_STATE_ON -> "on"
            VISUAL_STATE_OFF -> "off"
            else -> ""
        }
    }

    private fun atOf(
        node: NodeSnapshot,
        bounds: Rect?,
        act: List<String>,
        visual: Boolean,
        area: Rect?,
        screen: PixelSize,
    ): Point? {
        if (act.isEmpty()) return null
        if (visual) {
            val box = bounds ?: return null
            return Point((box.l + box.r) / 2, (box.t + box.b) / 2)
        }
        return normalizePoint(geometry.resolvePoint(node), area, screen)
    }

    private companion object {
        /** actionable 位并集（不含 SCROLLABLE）。 */
        const val ACTIONABLE_ANY =
            NodeFlags.CLICKABLE or NodeFlags.LONG_CLICKABLE or NodeFlags.EDITABLE or NodeFlags.CHECKABLE

        /** 归一化坐标量程（0–1000）。 */
        const val COORD_SCALE = 1000

        const val LABEL_SEP = " · "

        /** 视觉状态分类写在 stateDesc 的中文文案（vp8 §6.3），JSON 统一为 on/off。 */
        const val VISUAL_STATE_ON = "已开启"
        const val VISUAL_STATE_OFF = "已关闭"
    }
}
