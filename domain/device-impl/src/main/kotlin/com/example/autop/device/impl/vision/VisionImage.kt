/**
 * 视觉解析图像载体（docs/design/visual-screen-parsing §6）：ARGB 像素的纯 Kotlin 包装，
 * 供检测/OCR/实心度计算使用；Android 侧由 [visionImageOf] 从 Bitmap 构造。
 */
package com.example.autop.device.impl.vision

import android.graphics.Bitmap
import com.example.autop.device.Rect
import kotlin.math.abs

/** ARGB 通道位移/掩码与字节归一化系数（检测/OCR/亮度共用）。 */
internal const val RED_SHIFT: Int = 16
internal const val GREEN_SHIFT: Int = 8
internal const val BYTE_MASK: Int = 0xFF
internal const val BYTE_MAX: Float = 255.0f
internal const val COLOR_CHANNELS: Int = 3

/** 一帧解码后的图像（ARGB 像素，行优先）。 */
internal class VisionImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    /** 亮度（0..255，Rec.601 近似）。 */
    fun luma(
        x: Int,
        y: Int,
    ): Int {
        val p = argb[y * width + x]
        val r = (p shr RED_SHIFT) and BYTE_MASK
        val g = (p shr GREEN_SHIFT) and BYTE_MASK
        val b = p and BYTE_MASK
        return (R_LUMA * r + G_LUMA * g + B_LUMA * b) / LUMA_SCALE
    }

    /**
     * 框内前景占比：以框边框环的中位亮度为背景，统计偏离超过 [FILL_DELTA] 的像素比例。
     * 实心按钮高（整块偏离）、文字/白底框低（仅字形偏离），供子类型细化实心度判据。
     */
    fun solidRatio(bounds: Rect): Double {
        val box = bounds.intersect(0, 0, width, height)
        if (box.area == 0) return 0.0
        val bg = borderMedian(box)
        var foreground = 0
        for (y in box.t until box.b) {
            for (x in box.l until box.r) {
                if (abs(luma(x, y) - bg) > FILL_DELTA) foreground += 1
            }
        }
        return foreground.toDouble() / box.area
    }

    /** 框边框环像素亮度的中位数（背景估计）。 */
    private fun borderMedian(box: Rect): Int {
        val samples = mutableListOf<Int>()
        for (x in box.l until box.r) {
            samples += luma(x, box.t)
            samples += luma(x, box.b - 1)
        }
        for (y in box.t until box.b) {
            samples += luma(box.l, y)
            samples += luma(box.r - 1, y)
        }
        return samples.sorted()[samples.size / 2]
    }

    private companion object {
        const val R_LUMA = 77
        const val G_LUMA = 150
        const val B_LUMA = 29
        const val LUMA_SCALE = 256
        const val FILL_DELTA = 40
    }
}

/** ARGB 像素拷贝（Android 侧构造 [VisionImage] 的唯一入口）。 */
internal fun visionImageOf(bitmap: Bitmap): VisionImage {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return VisionImage(bitmap.width, bitmap.height, pixels)
}

/** 矩形裁剪到屏幕范围内（越界部分丢弃）。 */
internal fun Rect.intersect(
    l: Int,
    t: Int,
    r: Int,
    b: Int,
): Rect {
    val nl = maxOf(this.l, l)
    val nt = maxOf(this.t, t)
    val nr = minOf(this.r, r)
    val nb = minOf(this.b, b)
    return if (nr > nl && nb > nt) Rect(nl, nt, nr, nb) else Rect(0, 0, 0, 0)
}
