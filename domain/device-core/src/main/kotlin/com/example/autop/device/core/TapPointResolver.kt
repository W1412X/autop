/**
 * 点击点解析（docs/tech/05 §3.8，R1/A4）：手势点击的回退点不取 bounds 中心，
 * 避开被浮层/浮起的可点击兄弟盖住的位置。纯逻辑，与 [LocatorMatcher] 同层。
 * [Geometry] 复用一次 [SnapshotIndex] 与窗口归属，供一次序列化内全部节点批量取点。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot

/** 手势点击的回退选点。 */
public object TapPointResolver {
    /** 一次快照的取点上下文：索引、窗口归属与可点击候选只建一次。 */
    public class Geometry(
        snap: ScreenSnapshot,
    ) {
        private val index = SnapshotIndex(snap)
        private val windows = windowsOf(snap)
        private val clickable = snap.nodes.filter { it.flags and NodeFlags.CLICKABLE != 0 }

        /**
         * `resolvePoint(target)`（docs/tech/05 §3.8）：
         * 候选点 = bounds 内的中心点与四分点（中心优先）；返回第一个不被任何遮挡者覆盖的候选点；
         * 全部被盖 → 返回中心点（尽力而为，不报错）。
         */
        public fun resolvePoint(target: NodeSnapshot): Point {
            val candidates = candidatesOf(target.bounds)
            val occluders = occludersOf(target)
            return candidates.firstOrNull { point -> occluders.none { contains(it.bounds, point) } }
                ?: candidates.first()
        }

        /** 中心点 + 四分点（25%/75% 组合），中心优先。 */
        private fun candidatesOf(bounds: Rect): List<Point> {
            val q1x = bounds.l + bounds.width / QUARTER_DIV
            val q3x = bounds.l + bounds.width * QUARTER_3 / QUARTER_DIV
            val q1y = bounds.t + bounds.height / QUARTER_DIV
            val q3y = bounds.t + bounds.height * QUARTER_3 / QUARTER_DIV
            return listOf(
                Point(bounds.centerX, bounds.centerY),
                Point(q1x, q1y),
                Point(q1x, q3y),
                Point(q3x, q1y),
                Point(q3x, q3y),
            )
        }

        /** 遮挡者 = CLICKABLE、非亲缘、z 序高于目标的节点（docs/tech/05 §3.8）。 */
        private fun occludersOf(target: NodeSnapshot): List<NodeSnapshot> = clickable.filter { isOccluder(target, it) }

        private fun isOccluder(
            target: NodeSnapshot,
            node: NodeSnapshot,
        ): Boolean {
            if (node.idx == target.idx) return false
            val unrelated = !index.isDescendant(node.idx, target.idx) && !index.isDescendant(target.idx, node.idx)
            return unrelated && higherZ(target, node)
        }

        /** z 序更高：同父兄弟按 drawingOrder（null 视作 0），或窗口 layer 更高的节点。 */
        private fun higherZ(
            target: NodeSnapshot,
            node: NodeSnapshot,
        ): Boolean {
            val targetWin = windows[target.idx]
            val nodeWin = windows[node.idx]
            if (targetWin != null && nodeWin != null && targetWin.windowId != nodeWin.windowId) {
                return nodeWin.layer > targetWin.layer
            }
            val nodeOrder = node.drawingOrder ?: 0
            val targetOrder = target.drawingOrder ?: 0
            return node.parent == target.parent && nodeOrder > targetOrder
        }

        private fun contains(
            bounds: Rect,
            point: Point,
        ): Boolean {
            val xIn = point.x >= bounds.l && point.x < bounds.r
            val yIn = point.y >= bounds.t && point.y < bounds.b
            return xIn && yIn
        }
    }

    /** idx → 所属窗口（窗口根子树归属）。 */
    private fun windowsOf(snap: ScreenSnapshot): Map<Int, WindowSnapshot> {
        val byIdx = snap.nodes.associateBy { it.idx }
        val out = HashMap<Int, WindowSnapshot>()
        for (window in snap.windows) {
            val root = window.rootIdx?.let { byIdx[it] } ?: continue
            markWindow(root, window, byIdx, out)
        }
        return out
    }

    private fun markWindow(
        node: NodeSnapshot,
        window: WindowSnapshot,
        byIdx: Map<Int, NodeSnapshot>,
        out: MutableMap<Int, WindowSnapshot>,
    ) {
        out[node.idx] = window
        for (child in node.children) {
            byIdx[child]?.let { markWindow(it, window, byIdx, out) }
        }
    }

    private const val QUARTER_DIV = 4
    private const val QUARTER_3 = 3
}
