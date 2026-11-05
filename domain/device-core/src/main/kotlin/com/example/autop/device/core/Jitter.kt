/**
 * 拟人化抖动（docs/design/observation-modes-coordinate-actions §5.2）：纯函数，
 * 随机源由调用方注入；结果钳在命中节点与基准区内。
 */
package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.Rect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 点击落点抖动：以落点为圆心、[radius] 半径的圆内均匀取点。 */
public object TapJitter {
    /**
     * [Point] 为抖动后的实际落点。有 [hitBounds] 时先钳在命中节点内（保证小目标不点偏），
     * 最后统一钳在 [area] 内。
     */
    public fun point(
        x: Int,
        y: Int,
        radius: Int,
        hitBounds: Rect?,
        area: Rect,
        random: () -> Double,
    ): Point {
        if (radius <= 0) return Point(x, y).clampInside(hitBounds).clampTo(area)
        val r = radius.toDouble() * sqrt(random().coerceIn(0.0, 1.0))
        val angle = 2 * PI * random().coerceIn(0.0, 1.0)
        val jittered =
            Point(
                x = x + (r * cos(angle)).roundToInt(),
                y = y + (r * sin(angle)).roundToInt(),
            )
        return jittered.clampInside(hitBounds).clampTo(area)
    }
}

/** 滑动端点抖动：垂直方向与沿滑动方向各自取分量，整体位移不超过 [radius]。 */
public object SwipeJitter {
    /** 起点、终点各自独立抖动，再钳在 [area] 内；方向分量只影响长度（不超过 [radius]）。 */
    public fun endpoints(
        start: Point,
        end: Point,
        radius: Int,
        area: Rect,
        random: () -> Double,
    ): Pair<Point, Point> {
        if (radius <= 0) return start.clampTo(area) to end.clampTo(area)
        val dx = (end.x - start.x).toDouble()
        val dy = (end.y - start.y).toDouble()
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0.0) {
            return jitterCircle(start, radius, random).clampTo(area) to
                jitterCircle(end, radius, random).clampTo(area)
        }
        val ux = dx / length
        val uy = dy / length
        val px = -uy
        val py = ux
        val (startPerp, startAlong) = offsets(radius, random)
        val (endPerp, endAlong) = offsets(radius, random)
        val jitteredStart =
            Point(
                x = (start.x + px * startPerp + ux * startAlong).roundToInt(),
                y = (start.y + py * startPerp + uy * startAlong).roundToInt(),
            )
        val jitteredEnd =
            Point(
                x = (end.x + px * endPerp + ux * endAlong).roundToInt(),
                y = (end.y + py * endPerp + uy * endAlong).roundToInt(),
            )
        return jitteredStart.clampTo(area) to jitteredEnd.clampTo(area)
    }

    /** 圆内均匀取点分解为（垂直分量，沿方向分量）。 */
    private fun offsets(
        radius: Int,
        random: () -> Double,
    ): Pair<Double, Double> {
        val distance = radius.toDouble() * sqrt(random().coerceIn(0.0, 1.0))
        val angle = 2 * PI * random().coerceIn(0.0, 1.0)
        return (distance * sin(angle)) to (distance * cos(angle))
    }

    private fun jitterCircle(
        point: Point,
        radius: Int,
        random: () -> Double,
    ): Point {
        val distance = radius.toDouble() * sqrt(random().coerceIn(0.0, 1.0))
        val angle = 2 * PI * random().coerceIn(0.0, 1.0)
        return Point(
            x = point.x + (distance * cos(angle)).roundToInt(),
            y = point.y + (distance * sin(angle)).roundToInt(),
        )
    }
}

/** 钳在矩形内（右/下沿为开区间：r-1/b-1）。 */
public fun Point.clampTo(rect: Rect): Point =
    Point(
        x = x.coerceIn(rect.l, maxOf(rect.l, rect.r - 1)),
        y = y.coerceIn(rect.t, maxOf(rect.t, rect.b - 1)),
    )

private fun Point.clampInside(rect: Rect?): Point = if (rect == null) this else clampTo(rect)
