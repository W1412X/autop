/**
 * PP-OCR 前后处理（vp9 §4.1，PP-OCRv5 server rec）：rec 裁剪/切窗/预处理与 CTC 编解码。
 * v5 预处理：rec 输入 BGR 通道、(x/255)（导出图内置归一化）；纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.ceil
import kotlin.math.floor

/** rec 识别的候选文本框（框＋检测置信度）。 */
internal data class OcrBox(
    val bounds: Rect,
    val score: Double,
)

/** PP-OCR 编解码器。 */
internal object PpOcrCodec {
    /** rec 输入形状（官方口径：48 高、宽按比例 ≤320。N211 曾升 64×426，越出训练分布致 CTC 全空，已回退）。 */
    const val REC_HEIGHT: Int = 48
    const val REC_WIDTH: Int = 320

    /** 单窗最大宽高比（[REC_WIDTH]/[REC_HEIGHT]，超出切窗防截断）。 */
    val REC_MAX_ASPECT: Double = REC_WIDTH.toDouble() / REC_HEIGHT

    /** rec 归一化：模型导出时已内置归一化（inference.yml 无 NormalizeImage op），输入为 x/255（0..1）。
     * 曾按训练侧文档口径用 (x/255−0.5)/0.5，实测同一裁剪两种口径在 mobile/server rec 上
     * 乱码 vs 全对（微信日志 `[微l`、`cYWacYatw` 类乱码即此根因），以模型实际输入分布为准。 */
    private const val REC_SCALE: Float = 255.0f

    /** 双线性采样的半像素中心偏移（N211）。 */
    private const val HALF_PIXEL: Double = 0.5

    /** 通道字节位宽（ARGB 通道抽取）。 */
    private const val BYTE_BITS: Int = 8

    /** rec 框外扩比例分母（N211）：左右 1/20 宽、上下 1/10 高。 */
    private const val PAD_X_DIV: Int = 20
    private const val PAD_Y_DIV: Int = 10

    /** 切窗宽高比余量（不贴满 320，给采样留边）。 */
    private const val WINDOW_ASPECT_MARGIN: Double = 0.95

    /** 相邻切窗重叠比例（防切字）。 */
    private const val WINDOW_OVERLAP: Double = 0.15

    /** rec 裁剪框（N211 外扩：上下 10% 高、左右 5% 宽，保笔画，钳制图内）。 */
    fun recCrop(
        image: VisionImage,
        bounds: Rect,
    ): Rect = expandRec(bounds, image)

    /**
     * 长行切窗（长文本截断治理）：裁剪框宽高比超单窗上限时切成多个重叠窗口，
     * 每窗都符合模型输入几何（高 [REC_HEIGHT]、宽 ≤ [REC_WIDTH]，不再截断）；未超限返回单窗。
     */
    fun recWindows(crop: Rect): List<Rect> {
        val winW = minOf(crop.width, maxOf(1, (crop.height * REC_MAX_ASPECT * WINDOW_ASPECT_MARGIN).toInt()))
        if (winW >= crop.width) return listOf(crop)
        val n = ceil(crop.width.toDouble() / (winW * (1.0 - WINDOW_OVERLAP))).toInt().coerceAtLeast(2)
        return (0 until n).map { i ->
            val l = crop.l + (crop.width - winW) * i / (n - 1)
            Rect(l, crop.t, l + winW, crop.b)
        }
    }

    /**
     * rec 单窗输入（[crop] 为 [recCrop]／[recWindows] 产出的最终裁剪，不再外扩）：
     * 高恒 [REC_HEIGHT]、宽按比例 ≤ [REC_WIDTH]（与训练预处理一致）→ 双线性采样 → 右侧补零（0 像素即模型输入 0）。
     */
    fun recInput(
        image: VisionImage,
        crop: Rect,
    ): FloatArray {
        val srcW = maxOf(1, crop.width)
        val srcH = maxOf(1, crop.height)
        val dstH = REC_HEIGHT
        val dstW = minOf(REC_WIDTH, maxOf(1, dstH * srcW / srcH))
        val plane = REC_HEIGHT * REC_WIDTH
        val chw = FloatArray(COLOR_CHANNELS * plane)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sample = sampleRec(image, crop, x, y, dstW, dstH)
                val dst = y * REC_WIDTH + x
                chw[dst] = sample[2] / REC_SCALE
                chw[plane + dst] = sample[1] / REC_SCALE
                chw[2 * plane + dst] = sample[0] / REC_SCALE
            }
        }
        return chw
    }

    /** 框外扩（N211）：上下 10% 高、左右 5% 宽（至少 1px），钳制图内。 */
    private fun expandRec(
        bounds: Rect,
        image: VisionImage,
    ): Rect {
        val padX = maxOf(1, bounds.width / PAD_X_DIV)
        val padY = maxOf(1, bounds.height / PAD_Y_DIV)
        val l = maxOf(0, bounds.l - padX)
        val t = maxOf(0, bounds.t - padY)
        val r = minOf(image.width, bounds.r + padX)
        val b = minOf(image.height, bounds.b + padY)
        return if (r > l && b > t) Rect(l, t, r, b) else bounds
    }

    /** 双线性取样（半像素中心，与 [StateCropCodec] 同约定）→ RGB 0..255。 */
    private fun sampleRec(
        image: VisionImage,
        box: Rect,
        cx: Int,
        cy: Int,
        dstW: Int,
        dstH: Int,
    ): FloatArray {
        val sx = (cx + HALF_PIXEL) * box.width / dstW - HALF_PIXEL
        val sy = (cy + HALF_PIXEL) * box.height / dstH - HALF_PIXEL
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
        val out = FloatArray(COLOR_CHANNELS)
        for (c in 0 until COLOR_CHANNELS) {
            val top = lerpRec(channelRec(image, box, x0, y0, c), channelRec(image, box, x0 + 1, y0, c), fx)
            val bottom =
                lerpRec(channelRec(image, box, x0, y0 + 1, c), channelRec(image, box, x0 + 1, y0 + 1, c), fx)
            out[c] = lerpRec(top, bottom, fy)
        }
        return out
    }

    /** 框内 (x, y) 的通道值（越界钳制到框边，RGB 顺序）。 */
    private fun channelRec(
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

    private fun lerpRec(
        a: Float,
        b: Float,
        t: Double,
    ): Float = (a + (b - a) * t.coerceIn(0.0, 1.0).toFloat())

    /** 字典类数校验（vp9 §4.1）：CTC 类数＝charset 行数（含末尾空格行）＋1(blank)，不猜测。 */
    fun charsetMatches(
        classes: Int,
        charset: List<String>,
    ): Boolean = classes == charset.size + 1

    /** CTC 贪心解码：逐时刻 argmax，去重去 blank（索引 0），按 [charset] 映射字符。 */
    fun ctcDecode(
        logits: FloatArray,
        timesteps: Int,
        classes: Int,
        charset: List<String>,
    ): String {
        val out = StringBuilder()
        var last = -1
        for (t in 0 until timesteps) {
            val best = argmax(logits, t * classes, classes)
            if (best != 0 && best != last && best < charset.size + 1) out.append(charset[best - 1])
            last = best
        }
        return out.toString()
    }

    /** 找 [from, from+count) 的最大值索引。 */
    private fun argmax(
        data: FloatArray,
        from: Int,
        count: Int,
    ): Int {
        var best = from
        for (i in from + 1 until from + count) {
            if (data[i] > data[best]) best = i
        }
        return best - from
    }
}
