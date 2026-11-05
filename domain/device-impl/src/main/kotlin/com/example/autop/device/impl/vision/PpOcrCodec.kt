/**
 * PP-OCR 前后处理（vp9 §4.1，PP-OCRv5 mobile）：det 概率图→文本行框、rec→CTC 解码。
 * v5 预处理：det/rec 均 BGR 通道；det 用 ImageNet 均值方差，rec 用 (x/255−0.5)/0.5；
 * det 后处理按框内平均概率 box_thresh 过滤。UI 截图文本行近似水平，
 * det 后处理用轴对齐连通域（免旋转多边形）；纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.floor

/** det 产出的候选文本行（框＋框内平均概率）。 */
internal data class OcrBox(
    val bounds: Rect,
    val score: Double,
)

/** PP-OCR 编解码器。 */
internal object PpOcrCodec {
    /** det 输入最长边（PP-OCRv4 mobile 惯例 960，等比缩放）。 */
    const val DET_LIMIT_SIDE: Int = 960

    /** rec 输入形状（官方口径：48 高、宽按比例 ≤320。N211 曾升 64×426，越出训练分布致 CTC 全空，已回退）。 */
    const val REC_HEIGHT: Int = 48
    const val REC_WIDTH: Int = 320

    /** 概率图二值化阈值（DB `thresh`）。 */
    private const val BINARIZE: Double = 0.3

    /** 框内平均概率下限（DB `box_thresh`，低于此分数的连通域丢弃，vp9 §4.1）。 */
    private const val BOX_THRESH: Double = 0.6

    /** 连通域最小像素数（过滤噪声）。 */
    private const val MIN_PIXELS: Int = 4

    /** det 缩放后框外扩像素（补偿收缩）。 */
    private const val BOX_PAD: Int = 2

    /** det 输入边长对齐倍数（网络下采样要求）。 */
    private const val DET_ALIGN: Int = 32

    /** det 归一化（v5 ImageNet 均值方差，BGR 排列对应值）。 */
    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** rec 归一化（v5：(x/255 − 0.5)/0.5）。 */
    private const val REC_MEAN: Float = 0.5f
    private const val REC_STD: Float = 0.5f

    /** 双线性采样的半像素中心偏移（N211）。 */
    private const val HALF_PIXEL: Double = 0.5

    /** 通道字节位宽（ARGB 通道抽取）。 */
    private const val BYTE_BITS: Int = 8

    /** rec 框外扩比例分母（N211）：左右 1/20 宽、上下 1/10 高。 */
    private const val PAD_X_DIV: Int = 20
    private const val PAD_Y_DIV: Int = 10

    /** det 等比缩放参数（map 坐标 × [scale] = 原图坐标）。 */
    internal class DetMap(
        val prob: FloatArray,
        val width: Int,
        val height: Int,
        val scale: Double,
    )

    /** det 预处理几何：等比缩放到最长边 [DET_LIMIT_SIDE]，边长对齐 32 的倍数。 */
    fun detSize(
        imageWidth: Int,
        imageHeight: Int,
    ): Pair<Int, Int> {
        val side = maxOf(imageWidth, imageHeight)
        val ratio = DET_LIMIT_SIDE.toDouble() / side
        val w = roundUp32((imageWidth * ratio).toInt())
        val h = roundUp32((imageHeight * ratio).toInt())
        return w to h
    }

    /** det 后处理：概率图二值化 → 连通域 → 轴对齐文本行框（映射回原图坐标，box_thresh 过滤）。 */
    fun detBoxes(map: DetMap): List<OcrBox> {
        val mask = BooleanArray(map.width * map.height) { i -> map.prob[i] > BINARIZE }
        val seen = BooleanArray(mask.size)
        val boxes = mutableListOf<OcrBox>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val region = flood(mask, seen, start, map.width, map.height)
            val box = boundsOf(region, map.width, map.scale)
            if (box != null && region.size >= MIN_PIXELS) {
                val score = meanProb(map, region)
                if (score >= BOX_THRESH) boxes += OcrBox(box, score)
            }
        }
        return boxes
    }

    /** det 预处理（v5）：画布缩放到 mapW×mapH 的 CHW 张量，BGR 通道＋ImageNet 均值方差。 */
    fun detInput(
        image: VisionImage,
        mapW: Int,
        mapH: Int,
    ): FloatArray {
        val plane = mapW * mapH
        val chw = FloatArray(COLOR_CHANNELS * plane)
        for (y in 0 until mapH) {
            val sy = y * image.height / mapH
            for (x in 0 until mapW) {
                val sx = x * image.width / mapW
                val p = image.argb[sy * image.width + sx]
                val dst = y * mapW + x
                chw[dst] = normalizeDet((p and BYTE_MASK) / BYTE_MAX, 0)
                chw[plane + dst] = normalizeDet(((p shr GREEN_SHIFT) and BYTE_MASK) / BYTE_MAX, 1)
                chw[(COLOR_CHANNELS - 1) * plane + dst] =
                    normalizeDet(((p shr RED_SHIFT) and BYTE_MASK) / BYTE_MAX, 2)
            }
        }
        return chw
    }

    /**
     * rec 输入（官方口径几何）：框外扩保笔画（det 框切掉点/横是形近字误识主因）→
     * 高恒 [REC_HEIGHT]、宽按比例 ≤ [REC_WIDTH]（与训练预处理一致）→ 双线性采样 → 右侧补零。
     */
    fun recInput(
        image: VisionImage,
        bounds: Rect,
    ): FloatArray {
        val box = expandRec(bounds, image)
        val srcW = maxOf(1, box.width)
        val srcH = maxOf(1, box.height)
        val dstH = REC_HEIGHT
        val dstW = minOf(REC_WIDTH, maxOf(1, dstH * srcW / srcH))
        val plane = REC_HEIGHT * REC_WIDTH
        val chw = FloatArray(COLOR_CHANNELS * plane) { (0f - REC_MEAN) / REC_STD }
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sample = sampleRec(image, box, x, y, dstW, dstH)
                val dst = y * REC_WIDTH + x
                chw[dst] = normalizeRec(sample[2] / BYTE_MAX)
                chw[plane + dst] = normalizeRec(sample[1] / BYTE_MAX)
                chw[2 * plane + dst] = normalizeRec(sample[0] / BYTE_MAX)
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

    /** 深度优先泛洪收集连通域像素索引。 */
    private fun flood(
        mask: BooleanArray,
        seen: BooleanArray,
        start: Int,
        width: Int,
        height: Int,
    ): List<Int> {
        val region = mutableListOf<Int>()
        val stack = mutableListOf(start)
        seen[start] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            region += i
            val x = i % width
            val y = i / width
            if (x > 0) pushNeighbor(stack, mask, seen, i - 1)
            if (x + 1 < width) pushNeighbor(stack, mask, seen, i + 1)
            if (y > 0) pushNeighbor(stack, mask, seen, i - width)
            if (y + 1 < height) pushNeighbor(stack, mask, seen, i + width)
        }
        return region
    }

    /** 压栈未访问的前景邻域像素（越界由调用方按轴向排除）。 */
    private fun pushNeighbor(
        stack: MutableList<Int>,
        mask: BooleanArray,
        seen: BooleanArray,
        i: Int,
    ) {
        if (!mask[i] || seen[i]) return
        seen[i] = true
        stack += i
    }

    /** 连通域 → 原图坐标框（外扩 [BOX_PAD]）。 */
    private fun boundsOf(
        region: List<Int>,
        width: Int,
        scale: Double,
    ): Rect? {
        if (region.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0
        for (i in region) {
            val x = i % width
            val y = i / width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val l = ((minX - BOX_PAD) * scale).toInt()
        val t = ((minY - BOX_PAD) * scale).toInt()
        val r = ((maxX + 1 + BOX_PAD) * scale).toInt()
        val b = ((maxY + 1 + BOX_PAD) * scale).toInt()
        return Rect(l, t, r, b)
    }

    /** 框内平均概率（置信度）。 */
    private fun meanProb(
        map: DetMap,
        region: List<Int>,
    ): Double = region.sumOf { map.prob[it].toDouble() } / maxOf(1, region.size)

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

    /** 边长对齐 [DET_ALIGN] 的倍数（det 网络下采样要求）。 */
    private fun roundUp32(value: Int): Int = (value + DET_ALIGN - 1) / DET_ALIGN * DET_ALIGN

    /** det 通道归一化：(x − mean_c) / std_c（c 按 BGR 排列取值）。 */
    private fun normalizeDet(
        value: Float,
        channel: Int,
    ): Float = (value - DET_MEAN[channel]) / DET_STD[channel]

    /** rec 通道归一化：(x − 0.5) / 0.5。 */
    private fun normalizeRec(value: Float): Float = (value - REC_MEAN) / REC_STD
}
