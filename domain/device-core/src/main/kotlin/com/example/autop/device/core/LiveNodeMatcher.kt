/**
 * 节点工具的实时定位（coordinate-node-action-split §3.3）：按记录节点的类名、资源 id、
 * bounds 与窗口包名在实时快照里找同一节点；策略检查与动作都针对实时节点。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ScreenSnapshot

/** 实时节点匹配器。 */
public object LiveNodeMatcher {
    /**
     * 在 [live] 里找与 [recorded]（含记录时的 windowId 与子序号路径，供多候选消歧）对应的
     * 实时节点。候选：bounds 一致、所属窗口包名一致，且 className / viewId 一致
     * （记录侧为空的字段不比）。唯一命中返回；多于一个时优先 windowId 与子序号路径与
     * 记录一致的；仍无法确定返回 null（判失效）。
     */
    public fun find(
        live: ScreenSnapshot,
        recorded: NodeTarget,
    ): NodeTarget? {
        val index = SnapshotIndex(live)
        val expected = recorded.node
        val candidates = live.nodes.filter { matches(live, index, it, expected) }
        val best =
            when {
                candidates.isEmpty() -> return null
                candidates.size == 1 -> candidates.single()
                else ->
                    candidates
                        .filter {
                            index.windowIdOf(it.idx) == recorded.windowId &&
                                NodeTargets.of(index, it).path == recorded.path
                        }
                        .singleOrNull() ?: return null
            }
        return NodeTargets.of(index, best)
    }

    private fun matches(
        live: ScreenSnapshot,
        index: SnapshotIndex,
        candidate: NodeSnapshot,
        expected: NodeSnapshot,
    ): Boolean =
        candidate.bounds == expected.bounds &&
            packageOk(live, index, candidate, expected) &&
            fieldOk(expected.className, candidate.className) &&
            fieldOk(expected.viewId, candidate.viewId)

    /** 记录侧字段为空不比；非空必须一致。 */
    private fun fieldOk(
        expected: String?,
        candidate: String?,
    ): Boolean {
        val want = expected?.takeIf { it.isNotEmpty() } ?: return true
        return candidate?.takeIf { it.isNotEmpty() } == want
    }

    /** 记录侧包名为空不比；非空时与候选所属窗口包名（缺省取节点包名）一致。 */
    private fun packageOk(
        live: ScreenSnapshot,
        index: SnapshotIndex,
        candidate: NodeSnapshot,
        expected: NodeSnapshot,
    ): Boolean {
        val want = expected.packageName?.takeIf { it.isNotEmpty() } ?: return true
        val windowId = index.windowIdOf(candidate.idx)
        val got = live.windows.firstOrNull { it.windowId == windowId }?.packageName ?: candidate.packageName
        return got == want
    }
}
