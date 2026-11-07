/**
 * 差量观测（docs/design/observation-modes-coordinate-actions §3.4）：
 * 只有 a11y/visual 有差量；按稳定键配对，换页或变化比例超阈值时回退完整观测。
 */
package com.example.autop.device.core

import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenNode

/** 统一 JSON 差量生成。 */
public object ScreenJsonDiffer {
    /** 生成差量观测；无基线、换模式、换页或变化比例超过 `diffMaxRatio` 时返回完整观测。 */
    public fun diff(
        prev: SerializedScreen?,
        cur: SerializedScreen,
        config: SerializerConfig,
    ): Observation {
        val full = cur.observation
        val base = prev
        if (base == null || !comparable(base, cur)) return full
        // 只比较应用窗口：系统窗口（状态栏时钟/网速等）一直变，掺进来会让差量永远“有变化”。
        val prevNodes = base.model.appFlatten()
        val curNodes = cur.model.appFlatten()
        val prevByKey = prevNodes.filter { it.key.isNotEmpty() }.associateBy { it.key }
        val curByKey = curNodes.filter { it.key.isNotEmpty() }.associateBy { it.key }
        val added = curNodes.filter { it.key.isEmpty() || it.key !in prevByKey }
        val removed = prevNodes.filter { it.key.isEmpty() || it.key !in curByKey }
        val changed =
            curNodes.filter { node ->
                val before = prevByKey[node.key] ?: return@filter false
                !sameNode(before, node)
            }
        val ratio = (added.size + removed.size + changed.size).toDouble() / maxOf(1, curNodes.size)
        if (ratio > config.diffMaxRatio) return full
        return full.copy(
            mode = ObservationMode.DIFF,
            text = ScreenJsonRenderer.renderDiff(cur.model.mode, added, changed, removed),
        )
    }

    /** 同一观测模式、同一页面且非 llm 时才生成差量。 */
    private fun comparable(
        base: SerializedScreen,
        cur: SerializedScreen,
    ): Boolean =
        cur.model.mode != ObserveMode.LLM &&
            base.model.mode == cur.model.mode &&
            base.observation.packageName == cur.observation.packageName &&
            base.observation.activity == cur.observation.activity

    /** 节点内容是否相同（忽略 kids 与内部 key）。 */
    private fun sameNode(
        a: ScreenNode,
        b: ScreenNode,
    ): Boolean = a.copy(kids = emptyList(), key = "") == b.copy(kids = emptyList(), key = "")
}
