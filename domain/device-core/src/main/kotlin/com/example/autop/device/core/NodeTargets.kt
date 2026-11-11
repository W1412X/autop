/**
 * 节点在实时快照里的定位（coordinate-node-action-split §3.3）：windowId + 根到节点的
 * 子序号路径（真实子序号）。与 [NodeActionSelector] 共用同一实现。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ScreenSnapshot

/** 节点目标定位。 */
public object NodeTargets {
    /** [node]（来自 [snapshot]）的定位：窗口 id 与根到节点的子序号路径。 */
    public fun of(
        snapshot: ScreenSnapshot,
        node: NodeSnapshot,
    ): NodeTarget = of(SnapshotIndex(snapshot), node)

    /** [node]（来自 [index] 的快照）的定位：窗口 id 与根到节点的子序号路径。 */
    public fun of(
        index: SnapshotIndex,
        node: NodeSnapshot,
    ): NodeTarget = NodeTarget(node, index.windowIdOf(node.idx), liveChildPath(index, node))

    /**
     * 窗口根到节点的实时子序号路径。用采集时记录的 [NodeSnapshot.childIndex]（真实子序号），
     * 不用 [SnapshotIndex.pathOf]（它是快照 children 列表位置，采集时跳过的 null 子节点会让两者错位）。
     */
    private fun liveChildPath(
        index: SnapshotIndex,
        node: NodeSnapshot,
    ): List<Int> {
        val path = ArrayList<Int>()
        var current: NodeSnapshot = node
        while (true) {
            val parent = current.parent?.let { index.node(it) } ?: break
            path += current.childIndex
            current = parent
        }
        path.reverse()
        return path
    }
}
