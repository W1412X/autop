/**
 * a11y 观测的节点 id 分配（coordinate-node-action-split §3.2）：Run 内单调递增不复用，
 * 经 [ScreenNodeMatcher] 配对跨差量保持；id 不输出进指纹、不参与配对与差量本身。
 */
package com.example.autop.device.core

import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode

/** 节点 id 分配器（观测序列化出口注入；回放探针等不分配的调用点不传）。 */
public fun interface NodeIdAssigner {
    /**
     * 给 [model] 的可操作节点分配 id。[packageName]、[activity] 为本次观测的前台信息，
     * 与分配器记录的上一次观测信息一致时才做配对。
     */
    public fun assign(
        model: ScreenModel,
        packageName: String?,
        activity: String?,
    ): ScreenModel
}

/** id 分配实现（§3.2）。 */
public object NodeIds {
    /**
     * 给 [model]（a11y）的可操作节点分配 id：
     * 与 [prev] 配对成功的沿用 prev 节点的 id（prev 无 id 时领新 id），
     * 其余可操作节点从 [next] 领新 id；[prevComparable] 为 false 或非 a11y 时不配对，
     * 全部可操作节点领新 id。不可变重建；不修改 `ref`/`key`，非可操作节点不分配。
     */
    public fun assign(
        model: ScreenModel,
        prev: ScreenModel?,
        prevComparable: Boolean,
        tolerance: Int,
        volatilePatterns: List<Regex>,
        next: () -> Int,
    ): ScreenModel {
        if (model.mode != ObserveMode.A11Y) return model
        val cur = model.flatten()
        val ids = arrayOfNulls<Int>(cur.size)
        val prevNodes = if (prevComparable && prev?.mode == ObserveMode.A11Y) prev.flatten() else null
        if (prevNodes != null) {
            for ((pi, ci) in ScreenNodeMatcher.match(prevNodes, cur, tolerance, volatilePatterns).pairs) {
                ids[ci] = prevNodes[pi].id
            }
        }
        for (i in cur.indices) {
            if (cur[i].act.isNotEmpty() && ids[i] == null) ids[i] = next()
        }
        return rebuild(model, ids)
    }

    /** 按 [ScreenModel.flatten] 的先序顺序消费 [ids]，不可变重建树。 */
    private fun rebuild(
        model: ScreenModel,
        ids: Array<Int?>,
    ): ScreenModel {
        var pos = 0
        fun rebuildNode(node: ScreenNode): ScreenNode {
            val id = ids[pos++]
            return node.copy(id = id ?: node.id, kids = node.kids.map { rebuildNode(it) })
        }
        return model.copy(windows = model.windows.map { it.copy(kids = it.kids.map { rebuildNode(it) }) })
    }
}
