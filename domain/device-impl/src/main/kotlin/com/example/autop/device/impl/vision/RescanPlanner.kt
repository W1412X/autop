/**
 * 补扫候选规划（vp9 §4.3，纯 Kotlin 可单测）：A 可交互叶子缺字缺图标 → E 跨框行涉及叶子 →
 * B 未被 OCR 覆盖的检测 text 框 → D item 子树无任何非空 text/icon → C 归属行最低 rec
 * 置信度 < lowConf。排除 image/progress/slider；勾选类只在 A 成立时补扫；同优先级面积
 * 升序；每帧最多 [plan] 的 maxBoxes，缓存命中不计名额。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.NodeFlags

/** 补扫候选。 */
internal data class RescanCandidate(
    /** [boxes] 索引。 */
    val boxIndex: Int,
    val kind: String,
    val priority: Int,
) {
    internal companion object {
        const val KIND_A: String = "A"
        const val KIND_E: String = "E"
        const val KIND_B: String = "B"
        const val KIND_D: String = "D"
        const val KIND_C: String = "C"
    }
}

/** 补扫规划器。 */
internal object RescanPlanner {
    /** 不补扫的类别（vp9 §4.3：image/progress/slider）。 */
    private val EXCLUDED: Set<String> =
        setOf(VisualCategory.IMAGE, VisualCategory.PROGRESS, VisualCategory.SLIDER, VisualCategory.SEEKBAR)

    /** 勾选类（只在 A 成立时补扫）。 */
    private val CHECKABLE: Set<String> =
        setOf(VisualCategory.CHECKBOX, VisualCategory.RADIO, VisualCategory.SWITCH)

    private const val INTERACTIVE_ANY: Int =
        NodeFlags.CLICKABLE or NodeFlags.LONG_CLICKABLE or NodeFlags.EDITABLE or
            NodeFlags.SCROLLABLE or NodeFlags.CHECKABLE

    /** 候选优先级（vp9 §4.3：A=1 … C=5，升序）。 */
    private const val PRIO_A: Int = 1
    private const val PRIO_E: Int = 2
    private const val PRIO_B: Int = 3
    private const val PRIO_D: Int = 4
    private const val PRIO_C: Int = 5

    /** 规划输入（vp9 §4.3）。 */
    internal data class Context(
        val kids: Map<Int, List<Int>> = emptyMap(),
        val leafLines: Map<Int, List<Int>> = emptyMap(),
        val iconOf: Map<Int, String> = emptyMap(),
        val spanLeaves: List<Int> = emptyList(),
        val lineConf: Map<Int, List<Double>> = emptyMap(),
        val coveredText: Set<Int> = emptySet(),
        val cached: Set<Int> = emptySet(),
    )

    /**
     * 规划（vp9 §4.3）：[boxes] 为吸收后检测框，[context] 为归属/图标/缓存上下文。
     * [context.cached] 命中的候选不占 [maxBoxes] 名额。
     */
    fun plan(
        boxes: List<VisualDetection>,
        context: Context,
        lowConf: Double,
        maxBoxes: Int,
    ): List<RescanCandidate> {
        val all =
            boxes.indices.mapNotNull { i ->
                kindOf(i, boxes, context, lowConf)
                    ?.let { (kind, priority) -> RescanCandidate(i, kind, priority) }
            }
        val sorted = all.sortedWith(compareBy({ it.priority }, { boxes[it.boxIndex].bounds.area }))
        val out = mutableListOf<RescanCandidate>()
        var quota = maxBoxes
        for (c in sorted) {
            if (c.boxIndex in context.cached) {
                out += c
            } else if (quota > 0) {
                quota -= 1
                out += c
            }
        }
        return out
    }

    /** 候选类（vp9 §4.3 表，命中即停）→ (kind, priority)；不补扫返回 null。 */
    private fun kindOf(
        i: Int,
        boxes: List<VisualDetection>,
        context: Context,
        lowConf: Double,
    ): Pair<String, Int>? {
        val det = boxes[i]
        val early = earlyKind(i, det, context)
        return when {
            det.category in EXCLUDED -> null
            // 勾选类只在 A 成立时补扫（vp9 §4.3）。
            det.category in CHECKABLE -> early
            else -> early ?: lateKind(i, det, context, lowConf)
        }
    }

    /** A（含勾选类口径）与 E 类判定。 */
    private fun earlyKind(
        i: Int,
        det: VisualDetection,
        context: Context,
    ): Pair<String, Int>? {
        val leaf = context.kids[i].isNullOrEmpty()
        val interactive = CategoryMapper.flagsOf(det.category) and INTERACTIVE_ANY != 0
        val textEmpty = context.leafLines[i].isNullOrEmpty()
        val iconEmpty = context.iconOf[i].isNullOrEmpty()
        val bareInteractive = leaf && interactive && textEmpty && iconEmpty
        return when {
            det.category in CHECKABLE -> if (bareInteractive) RescanCandidate.KIND_A to PRIO_A else null
            bareInteractive -> RescanCandidate.KIND_A to PRIO_A
            i in context.spanLeaves -> RescanCandidate.KIND_E to PRIO_E
            else -> null
        }
    }

    /** B/D/C 类判定。 */
    private fun lateKind(
        i: Int,
        det: VisualDetection,
        context: Context,
        lowConf: Double,
    ): Pair<String, Int>? {
        val leaf = context.kids[i].isNullOrEmpty()
        return when {
            leaf && det.category in VisualCategory.TEXT_KINDS && i !in context.coveredText ->
                RescanCandidate.KIND_B to PRIO_B
            det.category == VisualCategory.ITEM && subtreeEmpty(i, context) -> RescanCandidate.KIND_D to PRIO_D
            leaf && context.lineConf[i].orEmpty().any { it < lowConf } -> RescanCandidate.KIND_C to PRIO_C
            else -> null
        }
    }

    /** D 类：item 子树（含自身）内无任何非空 text/icon。 */
    private fun subtreeEmpty(
        root: Int,
        context: Context,
    ): Boolean {
        fun walk(i: Int): Boolean {
            val text = !context.leafLines[i].isNullOrEmpty()
            val icon = !context.iconOf[i].isNullOrEmpty()
            if (text || icon) return false
            return context.kids[i].orEmpty().all { walk(it) }
        }
        return walk(root)
    }
}
