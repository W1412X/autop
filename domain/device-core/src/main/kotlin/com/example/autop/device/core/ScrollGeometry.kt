/**
 * 滚动单步几何（ADR-0015）：一步 = 一次人手滑动。距离由容器尺寸决定，起止点整体落在容器内
 * 并留安全边距，不会越出可滚动区域，也不贴屏幕边缘（避开系统手势区）。纯函数。
 */
package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.Rect

/** 手指滑动方向（`up` = 手指从下往上滑，用来查看下方内容）。 */
public enum class ScrollDirection(
    public val id: String,
) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    /** 是否沿竖直轴滑动。 */
    public val vertical: Boolean
        get() = this == UP || this == DOWN

    public companion object {
        /** 按 id 解析；未知返回 null。 */
        public fun of(id: String?): ScrollDirection? = entries.firstOrNull { it.id == id }
    }
}

/** 几何参数（来自 `device.scroll`）。 */
public data class ScrollGeometryParams(
    val stepRatio: Double,
    val edgeInsetRatio: Double,
    val pixelsPerMs: Double,
    val minDurationMs: Long,
    val maxDurationMs: Long,
)

/** 一步滚动的起止点（像素）与时长。 */
public data class ScrollStep(
    val from: Point,
    val to: Point,
    val durationMs: Long,
)

/** 滚动单步几何。 */
public object ScrollGeometry {
    /**
     * 在 [container]（像素）内生成一步滚动。沿滚动轴以容器中心对称，垂直于滚动轴的位置
     * 取 [anchor]（钳在安全边距内），保证手指落在用户指向的那一列/一行。
     */
    public fun step(
        container: Rect,
        anchor: Point,
        direction: ScrollDirection,
        params: ScrollGeometryParams,
    ): ScrollStep {
        val axis = if (direction.vertical) container.height else container.width
        val cross = if (direction.vertical) container.width else container.height
        val inset = (axis * params.edgeInsetRatio).toInt()
        val crossInset = (cross * params.edgeInsetRatio).toInt()
        val usable = maxOf(1, axis - 2 * inset)
        val distance = (axis * params.stepRatio).toInt().coerceIn(1, usable)
        val half = distance / 2
        val duration = (distance / params.pixelsPerMs).toLong().coerceIn(params.minDurationMs, params.maxDurationMs)
        return if (direction.vertical) {
            val x =
                anchor.x.coerceIn(
                    container.l + crossInset,
                    maxOf(
                        container.l + crossInset,
                        container.r - 1 - crossInset,
                    ),
                )
            val cy = container.centerY
            val (fromY, toY) = if (direction == ScrollDirection.UP) cy + half to cy - half else cy - half to cy + half
            ScrollStep(Point(x, fromY), Point(x, toY), duration)
        } else {
            val y =
                anchor.y.coerceIn(
                    container.t + crossInset,
                    maxOf(
                        container.t + crossInset,
                        container.b - 1 - crossInset,
                    ),
                )
            val cx = container.centerX
            val (fromX, toX) = if (direction == ScrollDirection.LEFT) cx + half to cx - half else cx - half to cx + half
            ScrollStep(Point(fromX, y), Point(toX, y), duration)
        }
    }

    /** [a] 与 [b] 的交集；不相交时返回 [a]（容器与内容区错位时不让几何退化）。 */
    public fun intersectOrSelf(
        a: Rect,
        b: Rect,
    ): Rect {
        val l = maxOf(a.l, b.l)
        val t = maxOf(a.t, b.t)
        val r = minOf(a.r, b.r)
        val bottom = minOf(a.b, b.b)
        return if (r > l && bottom > t) Rect(l, t, r, bottom) else a
    }
}
