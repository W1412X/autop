/**
 * 节点动作目标选择（ADR-0015）：在快照里找“手指落在这个点会触发的那个节点”，纯函数。
 * 先取包含落点的最上层窗口（盖住下层的窗口即使没有可用节点也算挡住），
 * 再按绘制顺序在窗口内选最上层、最深的符合条件节点。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType

/** 目标节点的约束。 */
public data class NodeQuery(
    /** 节点必须带有的无障碍动作 id；null 不要求。 */
    val requiredActionId: Int? = null,
    /** 节点必须带有的 [NodeFlags] 位；0 不要求。 */
    val requiredFlag: Int = 0,
    /** 节点面积上限（像素²），超过视为全屏容器。 */
    val maxAreaPx: Long = Long.MAX_VALUE,
)

/** 选中的节点及其在实时树里的定位信息。 */
public data class NodeTarget(
    val node: NodeSnapshot,
    val windowId: Int,
    /** 窗口根到节点的子序号路径。 */
    val path: List<Int>,
)

/** 节点动作目标选择。 */
public object NodeActionSelector {
    /** 返回落点 [point]（像素）处符合 [query] 的节点；没有返回 null。 */
    public fun select(
        snapshot: ScreenSnapshot,
        point: Point,
        query: NodeQuery,
    ): NodeTarget? {
        val window = topWindowAt(snapshot, point) ?: return null
        val index = SnapshotIndex(snapshot)
        val best =
            snapshot.nodes
                .asSequence()
                .filter { index.windowIdOf(it.idx) == window.windowId }
                .filter { matches(it, point, query) }
                .maxWithOrNull { a, b -> compareZ(zKey(index, a), zKey(index, b)) }
                ?: return null
        return NodeTarget(best, window.windowId, NodeTargets.of(index, best).path)
    }

    /** 包含落点的最上层窗口。无障碍悬浮窗（本应用的盾、指示器）不拦截触摸，跳过。 */
    private fun topWindowAt(
        snapshot: ScreenSnapshot,
        point: Point,
    ): WindowSnapshot? =
        snapshot.windows
            .filter { it.type != WindowType.ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
            .firstOrNull { contains(it.bounds, point) }

    private fun matches(
        node: NodeSnapshot,
        point: Point,
        query: NodeQuery,
    ): Boolean =
        node.flags and NodeFlags.VISIBLE != 0 &&
            node.flags and NodeFlags.ENABLED != 0 &&
            node.flags and query.requiredFlag == query.requiredFlag &&
            (query.requiredActionId == null || query.requiredActionId in node.actions) &&
            contains(node.bounds, point) &&
            node.bounds.area.toLong() <= query.maxAreaPx

    private fun contains(
        rect: Rect,
        point: Point,
    ): Boolean = point.x >= rect.l && point.x < rect.r && point.y >= rect.t && point.y < rect.b

    /** 根到节点路径上每层的（绘制顺序，兄弟位置）；后绘制的兄弟在上层。 */
    private fun zKey(
        index: SnapshotIndex,
        node: NodeSnapshot,
    ): List<Pair<Int, Int>> {
        val chain = ArrayList<Pair<Int, Int>>()
        var current: NodeSnapshot? = node
        while (current != null) {
            val position = current.parent?.let { index.node(it)?.children?.indexOf(current.idx) } ?: 0
            chain += (current.drawingOrder ?: 0) to position
            current = current.parent?.let { index.node(it) }
        }
        chain.reverse()
        return chain
    }

    /** 逐层比较，先出现差异者决定；一方是另一方的前缀时，后代更深，视为更靠上。 */
    private fun compareZ(
        a: List<Pair<Int, Int>>,
        b: List<Pair<Int, Int>>,
    ): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val byOrder = a[i].first.compareTo(b[i].first)
            if (byOrder != 0) return byOrder
            val byPosition = a[i].second.compareTo(b[i].second)
            if (byPosition != 0) return byPosition
        }
        return a.size.compareTo(b.size)
    }
}
