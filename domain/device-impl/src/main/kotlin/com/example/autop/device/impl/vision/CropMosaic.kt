/**
 * 补扫裁剪与拼图（vp9 §4.4，纯 Kotlin 可单测）：裁剪区外扩 `padRatio×短边`（≥4px）且
 * 不越过相邻叶子的边；det 放大（字高不足 `targetTextPx` 时，倍率 ≤ `maxScale`）；
 * `mosaic=true` 时按高度降序 shelf 装箱进长边 ≤ 960 的画布（块间 `mosaicGap` 纯色间隔），
 * 框按块偏移/缩放映射回屏幕坐标；rec 一律从原图按回映框裁剪。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 补扫块（屏幕坐标）。 */
internal data class RescanCrop(
    /** 候选框索引（[RescanCandidate.boxIndex]）。 */
    val boxIndex: Int,
    /** 屏幕裁剪区（外扩、不越邻）。 */
    val crop: Rect,
    /** 估计字高（归属行高；null=未知，不放大）。 */
    val textHeight: Int? = null,
)

/** 拼图布局中的块（画布坐标＋回映参数）。 */
internal data class MosaicSlot(
    val boxIndex: Int,
    /** 块在画布上的位置（含放大）。 */
    val canvas: Rect,
    /** 原屏幕裁剪区。 */
    val crop: Rect,
    /** det 放大倍率（屏幕→画布）。 */
    val scale: Double,
)

/** 拼图画布布局。 */
internal data class Mosaic(
    val width: Int,
    val height: Int,
    val slots: List<MosaicSlot>,
)

/** 裁剪与拼图。 */
internal object CropMosaic {
    /** 裁剪外扩最小像素（§4.4）。 */
    private const val MIN_PAD: Int = 4

    /** 裁剪区（§4.4）：框外扩 max([MIN_PAD], [padRatio]×短边)，钳制屏幕内且不越相邻叶子的边。 */
    fun cropRegion(
        box: Rect,
        neighbors: List<Rect>,
        padRatio: Double,
        screenWidth: Int,
        screenHeight: Int,
    ): Rect {
        val pad = max(MIN_PAD, (padRatio * min(box.width, box.height)).roundToInt())
        var l = max(0, box.l - pad)
        var t = max(0, box.t - pad)
        var r = min(screenWidth, box.r + pad)
        var b = min(screenHeight, box.b + pad)
        for (n in neighbors) {
            if (verticalOverlap(n, box) && n.r <= box.l) l = max(l, n.r)
            if (verticalOverlap(n, box) && n.l >= box.r) r = min(r, n.l)
            if (horizontalOverlap(n, box) && n.b <= box.t) t = max(t, n.b)
            if (horizontalOverlap(n, box) && n.t >= box.b) b = min(b, n.t)
        }
        return if (r > l && b > t) Rect(l, t, r, b) else box
    }

    /** det 放大倍率（§4.4）：估计字高不足 [targetTextPx] 放大，≤ [maxScale]；未知字高不放大。 */
    fun detScale(
        textHeight: Int?,
        targetTextPx: Int,
        maxScale: Double,
    ): Double {
        if (textHeight == null || textHeight <= 0) return 1.0
        return (targetTextPx.toDouble() / textHeight).coerceIn(1.0, maxScale)
    }

    /**
     * 装箱拼图（§4.4）：块（含放大）按高度降序 shelf 装箱，画布宽＝[PpOcrCodec.DET_LIMIT_SIDE]，
     * 块间 [mosaicGap]；装不下的块不进画布（丢弃补扫）。
     */
    fun mosaic(
        crops: List<RescanCrop>,
        scales: Map<Int, Double>,
        mosaicGap: Int,
    ): Mosaic {
        val limit = PpOcrCodec.DET_LIMIT_SIDE
        val order =
            crops
                .map { it to (scales[it.boxIndex] ?: 1.0) }
                .sortedByDescending { (c, s) -> (c.crop.height * s).roundToInt() }
        val slots = mutableListOf<MosaicSlot>()
        var x = 0
        var y = 0
        var rowH = 0
        for ((crop, scale) in order) {
            val w = (crop.crop.width * scale).roundToInt()
            val h = (crop.crop.height * scale).roundToInt()
            if (w <= limit && h <= limit) {
                if (x > 0 && x + w > limit) {
                    x = 0
                    y += rowH + mosaicGap
                    rowH = 0
                }
                if (y + h <= limit) {
                    slots += MosaicSlot(crop.boxIndex, Rect(x, y, x + w, y + h), crop.crop, scale)
                    x += w + mosaicGap
                    rowH = max(rowH, h)
                }
            }
        }
        val height = slots.fold(0) { acc, s -> max(acc, s.canvas.b) }
        return Mosaic(limit, height, slots)
    }

    /** 画布坐标框 → 屏幕坐标（§4.4 回映：去块偏移、除以放大倍率、加裁剪区原点）。 */
    fun mapBack(
        canvasBox: Rect,
        slot: MosaicSlot,
    ): Rect {
        val l = ((canvasBox.l - slot.canvas.l) / slot.scale).roundToInt() + slot.crop.l
        val t = ((canvasBox.t - slot.canvas.t) / slot.scale).roundToInt() + slot.crop.t
        val r = ((canvasBox.r - slot.canvas.l) / slot.scale).roundToInt() + slot.crop.l
        val b = ((canvasBox.b - slot.canvas.t) / slot.scale).roundToInt() + slot.crop.t
        return Rect(l, t, max(l + 1, r), max(t + 1, b))
    }

    private fun verticalOverlap(
        a: Rect,
        b: Rect,
    ): Boolean = a.t < b.b && a.b > b.t

    private fun horizontalOverlap(
        a: Rect,
        b: Rect,
    ): Boolean = a.l < b.r && a.r > b.l
}
