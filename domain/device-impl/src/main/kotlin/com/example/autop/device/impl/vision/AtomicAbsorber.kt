/**
 * 原子类吸收（vp9 §3.1）：原子类（vp8 §1.2 所列 button/input/checkbox/radio/switch/
 * slider/progress/icon/image/text，v1 档对应键同理）不允许有子节点。落入原子框的子框：
 * icon → 删除并记入宿主 [Absorbed.iconRegion]（§5.2 裁图用）；text → 删除（其范围内的
 * OCR 行之后自然归父）；其他 → 按 vp8 §3.5 优先级保留高者、删低者。纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect

/** 原子吸收结果（[iconRegion] 为吸收的 icon 子框，§5.2 裁图用；多个取面积最大，N210）。 */
internal data class Absorbed(
    val detection: VisualDetection,
    val iconRegion: Rect?,
)

/** 原子类吸收器。 */
internal object AtomicAbsorber {
    /** 优先级（vp8 §3.5）：input > switch=checkbox=radio > slider > progress > button > item > icon > image > text。 */
    private val PRIORITY: Map<String, Int> =
        mapOf(
            VisualCategory.INPUT to 9,
            VisualCategory.INPUT_FIELD to 9,
            VisualCategory.SWITCH to 8,
            VisualCategory.CHECKBOX to 8,
            VisualCategory.RADIO to 8,
            VisualCategory.SLIDER to 7,
            VisualCategory.SEEKBAR to 7,
            VisualCategory.PROGRESS to 6,
            VisualCategory.BUTTON to 5,
            VisualCategory.ITEM to 4,
            VisualCategory.LIST_ITEM to 4,
            VisualCategory.ICON to 3,
            VisualCategory.ICON_CLICKABLE to 3,
            VisualCategory.IMAGE to 2,
            VisualCategory.TEXT to 1,
            VisualCategory.TEXT_LABEL to 1,
        )

    /** 原子类（vp8 §1.2）：不允许有子节点。 */
    private val ATOMIC: Set<String> = PRIORITY.keys - setOf(VisualCategory.ITEM, VisualCategory.LIST_ITEM)

    /** 优先级（未知类别最低；吸收裁决保留高者、删低者）。 */
    fun priorityOf(category: String): Int = PRIORITY[category] ?: 0

    /** 类别是否原子类（vp8 §1.2）。 */
    fun isAtomic(category: String): Boolean = category in ATOMIC

    /**
     * 吸收：删除落入原子框的子框，返回存活框（原顺序）；icon 子框记入宿主 [Absorbed.iconRegion]。
     * 两阶段：先按优先级裁决原子框内的异类子框（icon/text 除外），再把 icon/text 子框吸到
     * 最近原子宿主（宿主自身被吸收时区域随之转移，N210）。
     */
    fun absorb(detections: List<VisualDetection>): List<Absorbed> {
        val alive = detections.indices.toMutableSet()
        val regions = HashMap<Int, MutableList<Rect>>()
        pruneByPriority(detections, alive)
        absorbIconText(detections, alive, regions)
        return detections.mapIndexedNotNull { i, det ->
            if (i in alive) Absorbed(det, regions[i]?.maxByOrNull { it.area }) else null
        }
    }

    /** 阶段 1：原子框内落入的异类子框按优先级裁决（icon/text 子框留给阶段 2）。 */
    private fun pruneByPriority(
        detections: List<VisualDetection>,
        alive: MutableSet<Int>,
    ) {
        var changed = true
        while (changed) {
            changed = false
            for (a in alive.sorted()) {
                if (a !in alive || !isAtomic(detections[a].category)) continue
                changed = pruneAgainst(detections, alive, a) || changed
                if (a !in alive) changed = true
            }
        }
    }

    /** 对宿主 [a] 裁决其原子框内落入的异类子框；宿主被删（低优先级）返回后立即退出。 */
    private fun pruneAgainst(
        detections: List<VisualDetection>,
        alive: MutableSet<Int>,
        a: Int,
    ): Boolean {
        var changed = false
        val host = detections[a]
        val victims =
            alive.sorted().filter { b ->
                b != a &&
                    b in alive &&
                    detections[b].category !in VisualCategory.ICON_KINDS &&
                    detections[b].category !in VisualCategory.TEXT_KINDS &&
                    host.bounds.contains(detections[b].bounds)
            }
        for (b in victims) {
            if (priorityOf(host.category) >= priorityOf(detections[b].category)) {
                alive.remove(b)
            } else {
                alive.remove(a)
                return true
            }
            changed = true
        }
        return changed
    }

    /** 阶段 2：icon/text 子框吸到最近原子宿主；icon 记入宿主区域（宿主被吸收时随迁）。 */
    private fun absorbIconText(
        detections: List<VisualDetection>,
        alive: MutableSet<Int>,
        regions: MutableMap<Int, MutableList<Rect>>,
    ) {
        while (true) {
            val (host, child) = findAbsorbPair(detections, alive) ?: break
            alive.remove(child)
            val moved = regions.remove(child).orEmpty()
            val target = regions.getOrPut(host) { mutableListOf() }
            if (detections[child].category in VisualCategory.ICON_KINDS) target += detections[child].bounds
            target += moved
        }
    }

    /** 找一对（原子宿主, icon/text 子框）：子框面积大者先吸收，宿主取面积最小原子框。 */
    private fun findAbsorbPair(
        detections: List<VisualDetection>,
        alive: Set<Int>,
    ): Pair<Int, Int>? {
        val children =
            alive.filter {
                detections[it].category in VisualCategory.ICON_KINDS ||
                    detections[it].category in VisualCategory.TEXT_KINDS
            }
        val child = children.maxByOrNull { detections[it].bounds.area }
        val hosts =
            child?.let { c ->
                alive.filter {
                    it != c &&
                        isAtomic(detections[it].category) &&
                        detections[it].bounds.contains(detections[c].bounds)
                }
            }
        val host = hosts?.minByOrNull { detections[it].bounds.area }
        if (child == null || host == null) return null
        return host to child
    }
}
