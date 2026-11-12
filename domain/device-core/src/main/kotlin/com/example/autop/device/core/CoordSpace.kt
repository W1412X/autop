/**
 * 坐标空间与坐标校验（docs/tech/05 §12、docs/adr/0006，R1/A5）：
 * 模型输出坐标的参考空间由 `ModelProfile.coordSpace` 决定，换算与视觉块缩图为纯逻辑。
 */
package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.Rect
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 像素尺寸。 */
public data class PixelSize(
    val width: Int,
    val height: Int,
)

/** 协议视觉块缩图规则（docs/tech/05 §12.1）：算出“模型实际看到的尺寸”。 */
public object VisionSizing {
    /**
     * 把 [width]×[height] 缩到服务端缩图后的结果尺寸：长边先截到 [maxLongEdge]，
     * 块数（每 [blockSize] px 一块）超 [maxBlocks] 时按面积比开方继续等比收敛。只缩不放大。
     */
    public fun targetSize(
        width: Int,
        height: Int,
        blockSize: Int,
        maxBlocks: Int,
        maxLongEdge: Int,
    ): PixelSize {
        val blockLimit = maxBlocks.coerceAtLeast(1)
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        val longEdge = maxOf(w, h)
        if (longEdge > maxLongEdge) {
            val ratio = maxLongEdge.toDouble() / longEdge
            w = (w * ratio).toInt().coerceAtLeast(1)
            h = (h * ratio).toInt().coerceAtLeast(1)
        }
        while (blockCount(w, h, blockSize) > blockLimit) {
            val ratio = sqrt(blockLimit.toDouble() / blockCount(w, h, blockSize))
            w = (w * ratio).toInt().coerceAtLeast(1)
            h = (h * ratio).toInt().coerceAtLeast(1)
        }
        return PixelSize(w, h)
    }

    /** 视觉块数量：每 [blockSize] px 一块，向上取整后相乘。 */
    public fun blockCount(
        width: Int,
        height: Int,
        blockSize: Int,
    ): Int {
        val bw = ceil(width.toDouble() / blockSize).toInt().coerceAtLeast(1)
        val bh = ceil(height.toDouble() / blockSize).toInt().coerceAtLeast(1)
        return bw * bh
    }
}

/** 模型输出坐标的参考空间（docs/adr/0006）。 */
public enum class CoordSpace {
    /** 0–1000 归一化坐标。 */
    NORMALIZED_1000,

    /** 按“看到的图片”的像素坐标回答。 */
    IMAGE_PIXELS,
}

/**
 * 模型坐标 → 屏幕像素的换算器（docs/tech/05 §12.3–12.4）：
 * `NORMALIZED_1000` 与图无关；`IMAGE_PIXELS` 按“屏幕尺寸 ÷ 模型所见尺寸”反缩放。
 * N204：坐标基准区为内容区（截图可见区，已隐藏状态栏/导航栏）；[area] 为 null 时全屏。
 */
public data class CoordMapper(
    val space: CoordSpace,
    /** 屏幕尺寸（像素）。 */
    val screen: PixelSize,
    /** 模型“看到的图片”尺寸（IMAGE_PIXELS 的参考图，NORMALIZED_1000 下不参与换算）。 */
    val seen: PixelSize,
    /** 内容区（像素，N204）；null=全屏。归一化/图坐标都以此区为基准。 */
    val area: Rect? = null,
) {
    /**
     * 换算为屏幕像素；超范围返回 null（docs/tech/05 §12.3）。结果恒落在基准区内
     * （右/下沿钳到区内最后一个像素）——边界值按公式会落到区外一格（如 y=1000 落进
     * 导航条带），钳制保证“所见即所点”；手势前仍需裁剪到屏幕内。
     */
    public fun toScreen(
        x: Int,
        y: Int,
    ): Point? {
        if (!inRange(x, y)) return null
        val base = area ?: Rect(0, 0, screen.width, screen.height)
        val point =
            when (space) {
                CoordSpace.NORMALIZED_1000 ->
                    Point(
                        base.l + (x / NORMALIZED_MAX * base.width).roundToInt(),
                        base.t + (y / NORMALIZED_MAX * base.height).roundToInt(),
                    )
                CoordSpace.IMAGE_PIXELS ->
                    Point(
                        base.l + (x.toDouble() * base.width / seen.width).roundToInt(),
                        base.t + (y.toDouble() * base.height / seen.height).roundToInt(),
                    )
            }
        return Point(
            point.x.coerceIn(base.l, maxOf(base.l, base.r - 1)),
            point.y.coerceIn(base.t, maxOf(base.t, base.b - 1)),
        )
    }

    /** 合法范围：`NORMALIZED_1000` 为 0–1000；`IMAGE_PIXELS` 为 0 ≤ x < 图宽、0 ≤ y < 图高。 */
    public fun inRange(
        x: Int,
        y: Int,
    ): Boolean =
        when (space) {
            CoordSpace.NORMALIZED_1000 -> x in 0..NORMALIZED_LIMIT && y in 0..NORMALIZED_LIMIT
            CoordSpace.IMAGE_PIXELS -> x in 0 until seen.width && y in 0 until seen.height
        }

    /** 屏幕像素 → 0–1000（基准区内取整，钳在 0–1000）。 */
    public fun toNormalized(
        x: Int,
        y: Int,
    ): Point {
        val base = area ?: Rect(0, 0, screen.width, screen.height)
        return Point(
            x = ((x - base.l).toDouble() / base.width * NORMALIZED_MAX).roundToInt().coerceIn(0, NORMALIZED_LIMIT),
            y = ((y - base.t).toDouble() / base.height * NORMALIZED_MAX).roundToInt().coerceIn(0, NORMALIZED_LIMIT),
        )
    }

    private companion object {
        const val NORMALIZED_LIMIT = 1000
        const val NORMALIZED_MAX = 1000.0
    }
}
