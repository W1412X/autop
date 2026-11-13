/**
 * PP-OCR DB 文字检测前后处理（ADR-0026）：叶子裁剪（原分辨率）→ 模型输入 → 概率图 → 文字行框。
 * 口径对齐 PaddleOCR 官方与 poc/visual-parsing/tools/ocr_chain_compare.py：
 * 长边 ≤ [LIMIT_SIDE] 才缩小、边长向 [ALIGN] 取整（非 32 对齐 ORT Resize 会报错）、BGR 通道套 ImageNet 均值方差、
 * 二值化 [BIN_THRESH]、框分 [BOX_THRESH]、unclip [UNCLIP_RATIO]。
 * 与官方的差异：不引入 OpenCV/Clipper，用「8 连通域 → 轴对齐外接框 → 按公式外扩」代替「旋转最小外接矩形 + 多边形外扩」
 * （UI 文字基本水平，行框用于裁剪送 rec，不需要旋转框）。纯 Kotlin 可 JVM 单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** det 模型输入：CHW float（BGR 通道序）＋输入尺寸＋对应的整图区域。 */
internal class DetInput(
    val chw: FloatArray,
    val width: Int,
    val height: Int,
    /** 送入 det 的整图区域（已钳制到图内），输出坐标据此还原。 */
    val region: Rect,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** 文字检测编解码器。 */
internal object TextDetCodec {
    /** 长边超过此值才等比缩小（PaddleOCR limit_side_len）。 */
    const val LIMIT_SIDE: Int = 960

    /** 输入边长对齐（模型下采样步长）。 */
    const val ALIGN: Int = 32

    /** 概率图二值化阈值。 */
    const val BIN_THRESH: Float = 0.3f

    /** 连通域平均概率低于此值丢弃。 */
    const val BOX_THRESH: Double = 0.6

    /** DB unclip 外扩系数：距离 = 面积 × 系数 / 周长。 */
    const val UNCLIP_RATIO: Double = 1.5

    /** 外扩前连通域最短边下限（像素，概率图坐标）。 */
    const val MIN_SIDE_RAW: Int = 3

    /** 外扩后最短边下限。 */
    const val MIN_SIDE_UNCLIPPED: Int = 5

    /** 单次最多处理的连通域数（官方 max_candidates）。 */
    const val MAX_CANDIDATES: Int = 1000

    /** 8 连通邻域偏移。 */
    private const val NEIGHBOR_MIN: Int = -1
    private const val NEIGHBOR_MAX: Int = 1

    /** 外接框内像素到边界的半像素中心偏移。 */
    private const val HALF_PIXEL: Double = 0.5

    /** ImageNet 均值/方差，按 BGR 数组顺序直接套用（与 PaddleOCR 对 cv2 BGR 图的做法一致）。 */
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * 构造 det 输入。[region] 先钳制到图内；空区域返回 null。
     * 输入宽高 = round(边长 × 缩放 / 32) × 32（至少 32），双线性采样（半像素中心，越界取边）。
     */
    fun input(
        image: VisionImage,
        region: Rect,
    ): DetInput? {
        val box = region.intersect(0, 0, image.width, image.height)
        if (box.width <= 0 || box.height <= 0) return null
        val longSide = max(box.width, box.height)
        val ratio = if (longSide > LIMIT_SIDE) LIMIT_SIDE.toDouble() / longSide else 1.0
        val dstW = alignedSide(box.width, ratio)
        val dstH = alignedSide(box.height, ratio)
        return DetInput(sample(image, box, dstW, dstH), dstW, dstH, box, image.width, image.height)
    }

    /**
     * 概率图 → 文字行框（整图坐标，阅读序：行中心 y 再 x）。[prob] 为 `mapHeight × mapWidth` 行优先概率。
     * 步骤：二值化 → 8 连通域 → 丢最短边 < [MIN_SIDE_RAW] → 丢平均概率 < [BOX_THRESH] → 外扩 → 还原坐标并钳制到图内。
     */
    fun boxes(
        prob: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        input: DetInput,
    ): List<OcrBox> {
        if (mapWidth <= 0 || mapHeight <= 0 || prob.size < mapWidth * mapHeight) return emptyList()
        val mask = BooleanArray(mapWidth * mapHeight) { prob[it] > BIN_THRESH }
        val stack = IntArray(mask.size)
        val out = ArrayList<OcrBox>()
        var candidates = 0
        for (start in mask.indices) {
            // 超过 MAX_CANDIDATES 后不再处理新的连通域（仍线性扫过剩余像素，开销可忽略）。
            if (mask[start] && candidates < MAX_CANDIDATES) {
                candidates += 1
                val comp = fill(prob, mask, stack, start, mapWidth, mapHeight)
                boxOf(comp, mapWidth, mapHeight, input)?.let { out += it }
            }
        }
        return out.sortedWith(compareBy({ it.bounds.centerY }, { it.bounds.l }))
    }

    /** 边长 × 缩放 → 向 [ALIGN] 取整（四舍六入五成双，与 Python round 一致）。 */
    private fun alignedSide(
        side: Int,
        ratio: Double,
    ): Int = max(round(side * ratio / ALIGN).toInt() * ALIGN, ALIGN)

    /** 区域双线性缩放到 [dstW]×[dstH]，输出 BGR 通道序 CHW 并做 ImageNet 归一化。 */
    private fun sample(
        image: VisionImage,
        box: Rect,
        dstW: Int,
        dstH: Int,
    ): FloatArray {
        val plane = dstW * dstH
        val chw = FloatArray(COLOR_CHANNELS * plane)
        val x0 = IntArray(dstW)
        val x1 = IntArray(dstW)
        val fx = FloatArray(dstW)
        for (x in 0 until dstW) {
            val sx = (x + HALF_PIXEL) * box.width / dstW - HALF_PIXEL
            val f = floor(sx).toInt()
            x0[x] = f.coerceIn(0, box.width - 1)
            x1[x] = (f + 1).coerceIn(0, box.width - 1)
            fx[x] = (sx - f).toFloat()
        }
        for (y in 0 until dstH) {
            val sy = (y + HALF_PIXEL) * box.height / dstH - HALF_PIXEL
            val f = floor(sy).toInt()
            val rowTop = (box.t + f.coerceIn(0, box.height - 1)) * image.width + box.l
            val rowBottom = (box.t + (f + 1).coerceIn(0, box.height - 1)) * image.width + box.l
            val fy = (sy - f).toFloat()
            for (x in 0 until dstW) {
                val at = y * dstW + x
                for (c in 0 until COLOR_CHANNELS) {
                    val shift = c * BYTE_BITS // BGR：c=0 取蓝（低 8 位）
                    val tl = (image.argb[rowTop + x0[x]] shr shift) and BYTE_MASK
                    val tr = (image.argb[rowTop + x1[x]] shr shift) and BYTE_MASK
                    val bl = (image.argb[rowBottom + x0[x]] shr shift) and BYTE_MASK
                    val br = (image.argb[rowBottom + x1[x]] shr shift) and BYTE_MASK
                    val top = tl + (tr - tl) * fx[x]
                    val bottom = bl + (br - bl) * fx[x]
                    val value = (top + (bottom - top) * fy) / BYTE_MAX
                    chw[c * plane + at] = (value - MEAN[c]) / STD[c]
                }
            }
        }
        return chw
    }

    /** 连通域统计：概率图坐标下的外接范围（含端点）与平均概率。 */
    private class Component(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val meanProb: Double,
    )

    /** 从 [start] 起泛洪（8 连通），访问过的像素在 [mask] 里清零；[stack] 容量 ≥ 像素数即可。 */
    private fun fill(
        prob: FloatArray,
        mask: BooleanArray,
        stack: IntArray,
        start: Int,
        w: Int,
        h: Int,
    ): Component {
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var sum = 0.0
        var count = 0
        var top = 0
        stack[top++] = start
        mask[start] = false
        while (top > 0) {
            val p = stack[--top]
            val x = p % w
            val y = p / w
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            sum += prob[p]
            count += 1
            top = pushNeighbors(mask, stack, top, p, w, h)
        }
        return Component(minX, minY, maxX, maxY, sum / count)
    }

    /** 把像素 [p] 的未访问 8 邻域像素标记为已访问并压栈，返回新的栈顶位置。 */
    private fun pushNeighbors(
        mask: BooleanArray,
        stack: IntArray,
        top: Int,
        p: Int,
        w: Int,
        h: Int,
    ): Int {
        var next = top
        val x = p % w
        val y = p / w
        for (ny in max(0, y + NEIGHBOR_MIN)..min(h - 1, y + NEIGHBOR_MAX)) {
            for (nx in max(0, x + NEIGHBOR_MIN)..min(w - 1, x + NEIGHBOR_MAX)) {
                val q = ny * w + nx
                if (mask[q]) {
                    mask[q] = false
                    stack[next++] = q
                }
            }
        }
        return next
    }

    /**
     * 连通域 → 文字行框。尺寸按像素中心跨度（max − min）计，与官方 minAreaRect 的口径一致；
     * 外扩距离 = 面积 × [UNCLIP_RATIO] / 周长。
     */
    private fun boxOf(
        comp: Component,
        mapWidth: Int,
        mapHeight: Int,
        input: DetInput,
    ): OcrBox? {
        val spanW = comp.maxX - comp.minX
        val spanH = comp.maxY - comp.minY
        if (min(spanW, spanH) < MIN_SIDE_RAW || comp.meanProb < BOX_THRESH) return null
        val distance = spanW.toDouble() * spanH * UNCLIP_RATIO / (2.0 * (spanW + spanH))
        val l = comp.minX - distance
        val t = comp.minY - distance
        val r = comp.maxX + distance
        val b = comp.maxY + distance
        if (min(r - l, b - t) < MIN_SIDE_UNCLIPPED) return null
        val sx = input.region.width.toDouble() / mapWidth
        val sy = input.region.height.toDouble() / mapHeight
        val rect =
            Rect(
                floor(input.region.l + l * sx).toInt(),
                floor(input.region.t + t * sy).toInt(),
                ceil(input.region.l + r * sx).toInt(),
                ceil(input.region.t + b * sy).toInt(),
            ).intersect(0, 0, input.imageWidth, input.imageHeight)
        return if (rect.area == 0) null else OcrBox(rect, comp.meanProb)
    }
}

/** 通道字节位宽（ARGB 通道抽取）。 */
private const val BYTE_BITS: Int = 8
