/**
 * 单类检测结果的子类型细化（docs/design/visual-screen-parsing §6.1）：
 * 几何＋OCR 文字覆盖＋实心度回填 button/input/switch/text/list-item。
 * 多类检测器（后续微调）无需本档；行为与 PoC `vp/subtype.py` 一致。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.max
import kotlin.math.min

/** 子类型细化器。 */
internal object SubtypeRefiner {
    private const val SOLID_FILL: Double = 0.55
    private const val SWITCH_ASPECT_MIN: Double = 1.5
    private const val SWITCH_ASPECT_MAX: Double = 3.2
    private const val SWITCH_HEIGHT_MAX: Double = 0.07
    private const val SWITCH_WIDTH_MAX: Double = 0.25
    private const val TEXT_LEAF_COVER: Double = 0.4
    private const val TEXT_GROUP_COVER: Double = 0.25
    private const val INPUT_ASPECT_MIN: Double = 6.0
    private const val INPUT_HEIGHT_MAX: Double = 0.06
    private const val INPUT_COVER_MAX: Double = 0.15
    private const val ROW_HEIGHT_MIN: Double = 0.07
    private const val ROW_WIDTH_MIN: Double = 0.45

    /** 细化上下文（避免函数参数超限）。 */
    private class Context(
        val all: List<VisualDetection>,
        val lines: List<VisualOcrLine>,
        val width: Int,
        val height: Int,
        val solidOf: (Rect) -> Double,
    )

    /**
     * 细化单类检测结果。[solidOf] 返回框内前景占比（实心按钮高、文字/白底框低），
     * 由调用方基于像素计算后注入，保持本类纯 Kotlin 可单测。
     */
    fun refine(
        detections: List<VisualDetection>,
        ocrLines: List<VisualOcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        solidOf: (Rect) -> Double,
    ): List<VisualDetection> {
        val ctx = Context(detections, ocrLines, screenWidth, screenHeight, solidOf)
        return detections.map { it.copy(category = subtype(it, ctx)) }
    }

    /** 细化规则（顺序即优先级，PoC 验证版）。 */
    private fun subtype(
        det: VisualDetection,
        ctx: Context,
    ): String {
        val box = det.bounds
        val aspect = box.width.toDouble() / max(1, box.height)
        val descendants = ctx.all.filter { it !== det && box.contains(it.bounds) }
        val hasText = textOf(det, ctx.lines).isNotEmpty()
        val cover = textCover(det, ctx.lines)
        val solid = ctx.solidOf(box) >= SOLID_FILL
        return when {
            isSwitch(box, aspect, descendants, hasText, ctx) -> VisualCategory.SWITCH
            descendants.isEmpty() && cover >= TEXT_LEAF_COVER -> VisualCategory.TEXT_LABEL
            isTextGroup(descendants, cover, solid, ctx) -> VisualCategory.TEXT_LABEL
            descendants.isNotEmpty() || hasText -> bodyType(box, aspect, cover, ctx)
            else -> VisualCategory.ICON_CLICKABLE
        }
    }

    /** 药丸开关：紧凑无文本无子块。 */
    private fun isSwitch(
        box: Rect,
        aspect: Double,
        descendants: List<VisualDetection>,
        hasText: Boolean,
        ctx: Context,
    ): Boolean =
        !hasText &&
            descendants.isEmpty() &&
            aspect in SWITCH_ASPECT_MIN..SWITCH_ASPECT_MAX &&
            box.height <= SWITCH_HEIGHT_MAX * ctx.height &&
            box.width <= SWITCH_WIDTH_MAX * ctx.width

    /** 文字行组：子块全是文字叶子、自身文字密集且不实心。 */
    private fun isTextGroup(
        descendants: List<VisualDetection>,
        cover: Double,
        solid: Boolean,
        ctx: Context,
    ): Boolean =
        descendants.isNotEmpty() && !solid && cover >= TEXT_GROUP_COVER && descendants.all { isTextLeaf(it, ctx) }

    /** 主体控件细分：输入框/列表行/按钮。 */
    private fun bodyType(
        box: Rect,
        aspect: Double,
        cover: Double,
        ctx: Context,
    ): String =
        when {
            aspect >= INPUT_ASPECT_MIN && box.height <= INPUT_HEIGHT_MAX * ctx.height && cover <= INPUT_COVER_MAX ->
                VisualCategory.INPUT_FIELD
            box.height >= ROW_HEIGHT_MIN * ctx.height && box.width >= ROW_WIDTH_MIN * ctx.width ->
                VisualCategory.LIST_ITEM
            else -> VisualCategory.BUTTON
        }

    /** 纯文字叶子块：无子块且文字覆盖高。 */
    private fun isTextLeaf(
        det: VisualDetection,
        ctx: Context,
    ): Boolean =
        ctx.all.none { it !== det && det.bounds.contains(it.bounds) } && textCover(det, ctx.lines) >= TEXT_LEAF_COVER

    /** 中心落入框内的 OCR 文本拼接。 */
    private fun textOf(
        det: VisualDetection,
        lines: List<VisualOcrLine>,
    ): String = lines.filter { it.bounds.centerIn(det.bounds) }.joinToString("") { it.text }

    /** OCR 文本框面积占检测框面积比（上限 1.0）。 */
    private fun textCover(
        det: VisualDetection,
        lines: List<VisualOcrLine>,
    ): Double {
        val area = lines.filter { it.bounds.centerIn(det.bounds) }.sumOf { it.bounds.area }
        return min(1.0, area.toDouble() / max(1, det.bounds.area))
    }
}
