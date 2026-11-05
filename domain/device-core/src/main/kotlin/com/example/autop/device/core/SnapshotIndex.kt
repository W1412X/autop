/**
 * 快照索引（docs/tech/05 §3）：路径、兄弟序号与祖先关系的预计算，
 * 供序列化与定位匹配共用。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ScreenSnapshot

/** 对 [ScreenSnapshot.nodes] 的一次性索引。 */
public class SnapshotIndex(
    snapshot: ScreenSnapshot,
) {
    private val byIdx: Map<Int, NodeSnapshot> = snapshot.nodes.associateBy { it.idx }
    private val paths: Map<Int, List<Int>> = buildPaths(snapshot)
    private val windowIds: Map<Int, Int> = buildWindowIds(snapshot)

    /** 按 idx 取节点。 */
    public fun node(idx: Int): NodeSnapshot? = byIdx[idx]

    /** 从窗口根到节点的子序号路径（见 [walkPaths]）。 */
    public fun pathOf(idx: Int): List<Int> = paths[idx].orEmpty()

    /** 节点所在窗口的 windowId（游离树回退 0）。 */
    public fun windowIdOf(idx: Int): Int = windowIds[idx] ?: 0

    /**
     * 稳定键位置段（docs/tech/05 §3.4）：`windowId.子序号路径`，一屏内唯一（同窗口按
     * 子序号路径唯一、跨窗口按 windowId 区分）。替代旧的“同类兄弟序号”——同名同
     * 角色且兄弟序号相同而父级不同的节点（如列表各行里的同款开关）旧键会碰撞、短 ID
     * 复用，部分节点不可点（o9 夹具发现）。
     */
    public fun positionOf(idx: Int): String = (listOf(windowIdOf(idx)) + pathOf(idx)).joinToString(".")

    /** [idx] 是否为 [ancestor] 的后代（严格）。 */
    public fun isDescendant(
        idx: Int,
        ancestor: Int,
    ): Boolean {
        var cur: Int? = byIdx[idx]?.parent
        while (cur != null) {
            if (cur == ancestor) return true
            cur = byIdx[cur]?.parent
        }
        return false
    }

    private fun buildPaths(snapshot: ScreenSnapshot): Map<Int, List<Int>> {
        val paths = HashMap<Int, List<Int>>()
        for (root in snapshot.nodes.filter { it.parent == null }) {
            walkPaths(root, emptyList(), paths)
        }
        return paths
    }

    private fun walkPaths(
        node: NodeSnapshot,
        prefix: List<Int>,
        out: MutableMap<Int, List<Int>>,
    ) {
        // 根节点（无父）的路径为空；子节点路径 = 父路径 + 自身在父 children 列表中的位置。
        // 用列表位置而非 node.childIndex 字段：真实 a11y 下两者一致，但 childIndex 字段
        // 缺失/失真（如测试构造、部分厂商树）时列表位置仍唯一，避免路径塌缩键碰撞。
        out[node.idx] = prefix
        for ((position, child) in node.children.withIndex()) {
            byIdx[child]?.let { walkPaths(it, prefix + position, out) }
        }
    }

    private fun buildWindowIds(snapshot: ScreenSnapshot): Map<Int, Int> {
        val ids = HashMap<Int, Int>()
        for (window in snapshot.windows) {
            val root = window.rootIdx ?: continue
            stampWindow(root, window.windowId, ids)
        }
        // 窗口信息缺失的游离树按 0 号窗口处理，保证位置段仍唯一。
        for (node in snapshot.nodes.filter { it.parent == null }) {
            if (node.idx !in ids) stampWindow(node.idx, 0, ids)
        }
        return ids
    }

    private fun stampWindow(
        idx: Int,
        windowId: Int,
        out: MutableMap<Int, Int>,
    ) {
        if (idx in out) return
        out[idx] = windowId
        for (child in byIdx[idx]?.children.orEmpty()) stampWindow(child, windowId, out)
    }
}
