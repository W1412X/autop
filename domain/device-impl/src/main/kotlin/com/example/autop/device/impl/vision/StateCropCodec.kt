/**
 * 状态分类器裁剪预处理（vp8 §6.3，与 tools/build_state_crops.py / vp/state_cls.py 严格一致）：
 * 框四周外扩 4px → 等比缩放到高 48 → 宽不足 144 补灰（114）居中，超 144 则缩放到宽 144
 * 再上下补灰 → ImageNet 均值/方差归一化 → CHW。纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.floor
import kotlin.math.roundToInt

/** 状态裁剪编解码器。 */
internal object StateCropCodec {
    /** 画布尺寸（模型输入 [1,3,48,144]）。 */
    const val CROP_W: Int = 144
    const val CROP_H: Int = 48

    /** 框外扩像素。 */
    private const val EXPAND_PX: Int = 4

    /** 补灰值（YOLO/PIL 惯例 114）。 */
    private const val PAD_GRAY: Int = 114

    /** 双线性采样的半像素中心偏移。 */
    private const val HALF_PIXEL: Double = 0.5

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** 勾选类框 → `3×48×144` 平铺 CHW 归一化张量（与训练前处理一致）。 */
    fun crop(
        image: VisionImage,
        bounds: Rect,
    ): FloatArray {
        val box = expand(bounds, image)
        val plane = CROP_W * CROP_H
        val out = FloatArray(COLOR_CHANNELS * plane) { normalized(PAD_GRAY / BYTE_MAX, it / plane) }
        val (nw, nh) = fitCanvas(box.width, box.height)
        val ox = (CROP_W - nw) / 2
        val oy = (CROP_H - nh) / 2
        for (cy in 0 until nh) {
            for (cx in 0 until nw) {
                val sample = sample(image, box, cx, cy, nw, nh)
                val dst = (cy + oy) * CROP_W + (cx + ox)
                for (c in 0 until COLOR_CHANNELS) {
                    out[c * plane + dst] = normalized(sample[c] / BYTE_MAX, c)
                }
            }
        }
        return out
    }

    /** 框外扩 [EXPAND_PX] 并裁到屏幕内（空框按 1×1 兜底）。 */
    private fun expand(
        bounds: Rect,
        image: VisionImage,
    ): Rect {
        val l = maxOf(0, bounds.l - EXPAND_PX)
        val t = maxOf(0, bounds.t - EXPAND_PX)
        val r = minOf(image.width, bounds.r + EXPAND_PX)
        val b = minOf(image.height, bounds.b + EXPAND_PX)
        return if (r > l && b > t) Rect(l, t, r, b) else Rect(0, 0, 1, 1)
    }

    /** 等比缩放目标（高 48 优先；宽超 144 则改宽 144，PIL round 口径的四舍五入）。 */
    private fun fitCanvas(
        w: Int,
        h: Int,
    ): Pair<Int, Int> =
        if (w * CROP_H.toDouble() / h <= CROP_W) {
            maxOf(1, (w * CROP_H.toDouble() / h).roundToInt()) to CROP_H
        } else {
            CROP_W to maxOf(1, (h * CROP_W.toDouble() / w).roundToInt())
        }

    /** 双线性取样（半像素中心，与 PIL BILINEAR/torch align_corners=false 同约定）→ RGB 0..255。 */
    private fun sample(
        image: VisionImage,
        box: Rect,
        cx: Int,
        cy: Int,
        nw: Int,
        nh: Int,
    ): FloatArray {
        val sx = (cx + HALF_PIXEL) * box.width / nw - HALF_PIXEL
        val sy = (cy + HALF_PIXEL) * box.height / nh - HALF_PIXEL
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
        val out = FloatArray(COLOR_CHANNELS)
        for (c in 0 until COLOR_CHANNELS) {
            val top = lerp(channelAt(image, box, x0, y0, c), channelAt(image, box, x0 + 1, y0, c), fx)
            val bottom = lerp(channelAt(image, box, x0, y0 + 1, c), channelAt(image, box, x0 + 1, y0 + 1, c), fx)
            out[c] = lerp(top, bottom, fy)
        }
        return out
    }

    /** 框内 (x, y) 的通道值（越界钳制到框边）。 */
    private fun channelAt(
        image: VisionImage,
        box: Rect,
        x: Int,
        y: Int,
        c: Int,
    ): Float {
        val px = box.l + x.coerceIn(0, box.width - 1)
        val py = box.t + y.coerceIn(0, box.height - 1)
        val p = image.argb[py * image.width + px]
        return ((p shr ((COLOR_CHANNELS - 1 - c) * BYTE_BITS)) and BYTE_MASK).toFloat()
    }

    private fun lerp(
        a: Float,
        b: Float,
        t: Double,
    ): Float = (a + (b - a) * t.coerceIn(0.0, 1.0).toFloat())

    /** ImageNet 归一化（[v] 为 0..255 字节值/255）。 */
    private fun normalized(
        v: Float,
        channel: Int,
    ): Float = (v - MEAN[channel]) / STD[channel]
}

/** 通道字节位宽（ARGB 通道抽取共用）。 */
private const val BYTE_BITS: Int = 8
