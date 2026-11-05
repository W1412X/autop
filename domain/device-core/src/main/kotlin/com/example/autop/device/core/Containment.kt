/**
 * 矩形包含关系（docs/design/observation-modes-coordinate-actions §4.5 步骤 8）：
 * 视觉建树与 llm 元素建树共用同一“父＝包含自己的最小框”规则。
 */
package com.example.autop.device.core

import com.example.autop.device.Rect

/** 包含关系与 IoU。 */
public object Containment {
    /** 面积 ≥ [ratio] 落在本矩形内（设计稿 §6.2 的包含判定）。 */
    public fun Rect.containsRatio(
        other: Rect,
        ratio: Double = CONTAIN_RATIO,
    ): Boolean {
        val ix = maxOf(0, minOf(r, other.r) - maxOf(l, other.l))
        val iy = maxOf(0, minOf(b, other.b) - maxOf(t, other.t))
        val inter = ix.toLong() * iy
        return other.area > 0 && inter.toDouble() / other.area >= ratio
    }

    /** IoU（交并比）。 */
    public fun iou(
        a: Rect,
        b: Rect,
    ): Double {
        val ix = maxOf(0, minOf(a.r, b.r) - maxOf(a.l, b.l))
        val iy = maxOf(0, minOf(a.b, b.b) - maxOf(a.t, b.t))
        val inter = ix.toLong() * iy
        val union = a.area.toLong() + b.area - inter
        return if (union > 0) inter.toDouble() / union else 0.0
    }

    /** 父框下标：面积更大且包含 [node] 的最小框；没有返回 null。 */
    public fun parentIndexOf(
        node: Rect,
        boxes: List<Rect>,
    ): Int? =
        boxes
            .withIndex()
            .filter { (_, other) -> other.area > node.area && other.containsRatio(node) }
            .minByOrNull { it.value.area }
            ?.index

    private const val CONTAIN_RATIO: Double = 0.7
}
