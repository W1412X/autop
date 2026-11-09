/**
 * 按稳定键配对的节点差异集合：差量观测（device-core 的 ScreenJsonDiffer）与流程证据层
 * 共用（docs/design/flows-contracts-evidence §3.2：新增 / 消失 / 变化按稳定键配对计算）。
 *
 * 放在 device-api 而不是 device-core：CORE 模块之间不能互相依赖（docs/tech/01 §4），
 * 而 flow-core（证据层）与 device-core（差量观测）都要用它。
 */
package com.example.autop.device

/** 一组节点差异（按稳定键配对）。 */
public data class NodeDiffSets(
    val added: List<ScreenNode>,
    val removed: List<ScreenNode>,
    val changed: List<ScreenNode>,
)

/**
 * 计算两组节点的差异：按 [ScreenNode.key] 配对；键为空的节点无法配对，全部计入
 * 新增/消失；配对上但内容不同（忽略 kids 与内部 key）的计入变化。
 */
public fun nodeDiffSets(
    prev: List<ScreenNode>,
    cur: List<ScreenNode>,
): NodeDiffSets {
    val prevByKey = prev.filter { it.key.isNotEmpty() }.associateBy { it.key }
    val curByKey = cur.filter { it.key.isNotEmpty() }.associateBy { it.key }
    return NodeDiffSets(
        added = cur.filter { it.key.isEmpty() || it.key !in prevByKey },
        removed = prev.filter { it.key.isEmpty() || it.key !in curByKey },
        changed =
            cur.filter { node ->
                val before = prevByKey[node.key] ?: return@filter false
                !sameNode(before, node)
            },
    )
}

/** 节点内容是否相同（忽略 kids 与内部 key）。 */
private fun sameNode(
    a: ScreenNode,
    b: ScreenNode,
): Boolean = a.copy(kids = emptyList(), key = "") == b.copy(kids = emptyList(), key = "")
