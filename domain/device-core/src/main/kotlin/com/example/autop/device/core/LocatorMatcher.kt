/**
 * 定位匹配（docs/tech/05 §3.5）：按 a–d 四层规则在最新快照中找节点。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeLocator
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ScreenSnapshot

/** 定位器 → 候选节点的匹配逻辑（回放旧 `_locator` 解析调用）。 */
public object LocatorMatcher {
    /**
     * 按顺序取第一个非空的层：a. viewId+label → b. path+className → c. label+className →
     * d. viewId；多个候选取中心点离 [locator] 最近的；无候选返回 null。
     */
    public fun match(
        snapshot: ScreenSnapshot,
        locator: NodeLocator,
    ): NodeSnapshot? = matchAll(snapshot, locator).minByOrNull { centerDistance2(it, locator) }

    /** 第一个非空层的全部候选（多个最近距离并列时由调用方判为无法消歧）。 */
    public fun matchAll(
        snapshot: ScreenSnapshot,
        locator: NodeLocator,
    ): List<NodeSnapshot> {
        val index = SnapshotIndex(snapshot)
        val layers =
            listOf(
                snapshot.nodes.filter {
                    locator.viewId != null &&
                        it.viewId == locator.viewId &&
                        sameLabel(
                            it,
                            locator,
                        )
                },
                snapshot.nodes.filter { index.pathOf(it.idx) == locator.path && it.className == locator.className },
                snapshot.nodes.filter { sameLabel(it, locator) && it.className == locator.className },
                snapshot.nodes.filter { locator.viewId != null && it.viewId == locator.viewId },
            )
        return layers.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun sameLabel(
        node: NodeSnapshot,
        locator: NodeLocator,
    ): Boolean {
        val target = labelOf(locator.text, locator.desc) ?: return false
        return labelOf(node.text, node.contentDesc) == target
    }

    private fun labelOf(
        text: String?,
        desc: String?,
    ): String? = (text ?: desc)?.trim()?.takeIf { it.isNotEmpty() }

    private fun centerDistance2(
        node: NodeSnapshot,
        locator: NodeLocator,
    ): Long {
        val dx = (node.bounds.centerX - locator.bounds.centerX).toLong()
        val dy = (node.bounds.centerY - locator.bounds.centerY).toLong()
        return dx * dx + dy * dy
    }
}
