/**
 * 图标分类器输入/输出编解码（vp9 §5.3，纯 Kotlin 可单测）：letterbox 几何
 * （外扩 4px → 等比最长边 96 → 居中贴 96×96 灰底 114 → RGB ImageNet 归一化）、
 * logits softmax＋拒识规则（reject_labels/icon_conf/icon_margin）、metadata 校验。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/** 图标分类线材常量与编解码。 */
internal object IconClsWire {
    /** 模型输入边长（96×96，metadata `img_size` 校验基准）。 */
    const val INPUT_SIZE: Int = 96

    /** 补灰值（YOLO/PIL 惯例 114）。 */
    const val PAD_GRAY: Int = 114

    /** 裁剪区域外扩像素（§5.3）。 */
    const val EXPAND_PX: Int = 4

    /** 拒识阈值默认（metadata `icon_conf`，§9.2 用 labels_icon.yaml 现值 0.5）。 */
    const val DEFAULT_ICON_CONF: Double = 0.5

    /** top1−top2 间隔阈值默认（metadata `icon_margin`）。 */
    const val DEFAULT_ICON_MARGIN: Double = 0.1

    /** ImageNet 归一化（RGB 排列）。 */
    val NORM_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val NORM_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** 双线性采样的半像素中心偏移（与 PIL BILINEAR/align_corners=false 同约定）。 */
    private const val HALF_PIXEL: Double = 0.5

    /** metadata 浮点比对容差。 */
    private const val META_TOLERANCE: Double = 1e-6

    /**
     * letterbox（§5.3）：[region] 外扩 [EXPAND_PX]（不越图）→ 等比缩放到最长边
     * [INPUT_SIZE] → 居中贴 [PAD_GRAY] 灰底 → RGB 归一化 CHW（3×96×96）。
     */
    fun letterbox(
        image: VisionImage,
        region: Rect,
    ): FloatArray {
        val box = expand(region, image)
        val plane = INPUT_SIZE * INPUT_SIZE
        val out = FloatArray(COLOR_CHANNELS * plane) { normalized(PAD_GRAY / BYTE_MAX, it / plane) }
        val (nw, nh) = fitCanvas(box.width, box.height)
        val ox = (INPUT_SIZE - nw) / 2
        val oy = (INPUT_SIZE - nh) / 2
        for (cy in 0 until nh) {
            for (cx in 0 until nw) {
                val sample = sample(image, box, cx, cy, nw, nh)
                val dst = (cy + oy) * INPUT_SIZE + (cx + ox)
                for (c in 0 until COLOR_CHANNELS) {
                    out[c * plane + dst] = normalized(sample[c] / BYTE_MAX, c)
                }
            }
        }
        return out
    }

    /**
     * logits → 显示键（§5.3 拒识）：top1 ∈ [reject]、p_top1 < [iconConf]、
     * p_top1−p_top2 < [iconMargin] 之一即 null（`icon=""`）。
     */
    fun decode(
        logits: FloatArray,
        labels: List<String>,
        reject: Set<String>,
        iconConf: Double = DEFAULT_ICON_CONF,
        iconMargin: Double = DEFAULT_ICON_MARGIN,
    ): String? {
        val probs = if (labels.isEmpty() || logits.size != labels.size) DoubleArray(0) else softmax(logits)
        val top1 = if (probs.isEmpty()) null else probs.indices.maxBy { probs[it] }
        val top2 = top1?.let { t -> probs.indices.filter { it != t }.maxByOrNull { probs[it] } }
        val accepted =
            top1 != null &&
                labels[top1] !in reject &&
                probs[top1] >= iconConf &&
                (top2 == null || probs[top1] - probs[top2] >= iconMargin)
        return if (accepted) labels[top1] else null
    }

    /** metadata 校验（§5.3）：`img_size`/`normalize_mean`/`normalize_std` 与线材常量一致。 */
    fun metadataOk(meta: Map<String, String>): Boolean {
        val mean = parseFloats(meta["normalize_mean"])
        val std = parseFloats(meta["normalize_std"])
        return meta["img_size"] == "${INPUT_SIZE}x${INPUT_SIZE}" &&
            mean != null &&
            std != null &&
            matches(mean, NORM_MEAN) &&
            matches(std, NORM_STD)
    }

    /** logits → softmax 概率（数值稳定）。 */
    fun softmax(logits: FloatArray): DoubleArray {
        if (logits.isEmpty()) return DoubleArray(0)
        val max = logits.max()
        val exps = DoubleArray(logits.size) { exp((logits[it] - max).toDouble()) }
        val sum = exps.sum()
        return DoubleArray(exps.size) { exps[it] / sum }
    }

    /** 框外扩 [EXPAND_PX] 并裁到图内（空框按 1×1 兜底）。 */
    private fun expand(
        region: Rect,
        image: VisionImage,
    ): Rect {
        val l = maxOf(0, region.l - EXPAND_PX)
        val t = maxOf(0, region.t - EXPAND_PX)
        val r = minOf(image.width, region.r + EXPAND_PX)
        val b = minOf(image.height, region.b + EXPAND_PX)
        return if (r > l && b > t) Rect(l, t, r, b) else Rect(0, 0, 1, 1)
    }

    /** 等比缩放到最长边 [INPUT_SIZE]（PIL round 口径）。 */
    private fun fitCanvas(
        w: Int,
        h: Int,
    ): Pair<Int, Int> {
        val scale = INPUT_SIZE.toDouble() / maxOf(w, h)
        return maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
    }

    /** 双线性取样（半像素中心）→ RGB 0..255。 */
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

    /** 框内 (x, y) 的通道值（越界钳制到框边，RGB 顺序）。 */
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
    ): Float = (v - NORM_MEAN[channel]) / NORM_STD[channel]

    /** `"[0.485, 0.456, 0.406]"` → 浮点列表（非 3 项/非法返回 null）。 */
    private fun parseFloats(raw: String?): List<Double>? {
        val body = raw?.trim()?.removeSurrounding("[", "]")
        val parsed = body?.split(",")?.map { it.trim().toDoubleOrNull() }
        return parsed?.takeIf { it.size == COLOR_CHANNELS && null !in it }?.filterNotNull()
    }

    private fun matches(
        actual: List<Double>,
        expected: FloatArray,
    ): Boolean = actual.size == expected.size && actual.indices.all { abs(actual[it] - expected[it]) < META_TOLERANCE }
}

/** 通道字节位宽（ARGB 通道抽取共用）。 */
private const val BYTE_BITS: Int = 8
