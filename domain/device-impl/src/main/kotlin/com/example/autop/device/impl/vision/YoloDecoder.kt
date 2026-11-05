/**
 * YOLO 前后处理（docs/design/visual-screen-parsing §4）：letterbox 预处理＋原始输出解码＋NMS。
 * 纯 Kotlin 可单测；会话推理在 onnx 包的 ONNX 实现里。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.min

/** letterbox 预处理结果：CHW float 输入＋还原映射参数。 */
internal class YoloInput(
    val chw: FloatArray,
    val scale: Double,
    val padX: Int,
    val padY: Int,
)

/** YOLO 编解码器。 */
internal object YoloDecoder {
    /** 默认推理边长（GPA-GUI-Detector 导出形状 640）。 */
    const val IMG_SIZE: Int = 640

    /** letterbox 填充灰度（YOLO 惯例 114）。 */
    private const val PAD_GRAY: Int = 114

    /** NMS IoU 阈值。 */
    private const val NMS_IOU: Double = 0.45

    /** 输出通道数下限（4 框参＋1 类别分）。 */
    private const val MIN_CHANNELS: Int = 5

    /** xywh 框参通道数（其后为 K 个类别分）。 */
    private const val BOX_CHANNELS: Int = 4

    /** letterbox：等比缩放居中填充到 [imgSize]×[imgSize]，CHW 归一化到 0..1。 */
    fun letterbox(
        image: VisionImage,
        imgSize: Int = IMG_SIZE,
    ): YoloInput {
        val scale = min(imgSize.toDouble() / image.width, imgSize.toDouble() / image.height)
        val padX = ((imgSize - image.width * scale) / 2).toInt()
        val padY = ((imgSize - image.height * scale) / 2).toInt()
        val plane = imgSize * imgSize
        val chw = FloatArray(COLOR_CHANNELS * plane) { PAD_GRAY / BYTE_MAX }
        for (y in 0 until imgSize) {
            val sy = ((y - padY) / scale).toInt()
            if (sy < 0 || sy >= image.height) continue
            for (x in 0 until imgSize) {
                val sx = ((x - padX) / scale).toInt()
                if (sx < 0 || sx >= image.width) continue
                val p = image.argb[sy * image.width + sx]
                val dst = y * imgSize + x
                chw[dst] = ((p shr RED_SHIFT) and BYTE_MASK) / BYTE_MAX
                chw[plane + dst] = ((p shr GREEN_SHIFT) and BYTE_MASK) / BYTE_MAX
                chw[(COLOR_CHANNELS - 1) * plane + dst] = (p and BYTE_MASK) / BYTE_MAX
            }
        }
        return YoloInput(chw, scale, padX, padY)
    }

    /**
     * 解码原始输出 `[1, 4+K, A]`（xywh 中心格式＋K 类得分，ultralytics 导出已过激活）：
     * 映射回 [image] 范围内坐标，按 [minConfidence] 截断后 NMS。
     * [classNames] 非空时为 v2 多类直出（argmax 类别＋类别感知 NMS，vp8 §8.2）；
     * 为空时为单类兜底档（全部按 icon 出框，交 SubtypeRefiner 细化）。
     */
    fun decode(
        raw: FloatArray,
        anchors: Int,
        input: YoloInput,
        minConfidence: Double,
        image: VisionImage,
        classNames: List<String>? = null,
    ): List<VisualDetection> {
        val channels = raw.size / anchors
        if (channels < MIN_CHANNELS || anchors <= 0) return emptyList()
        val classes = channels - BOX_CHANNELS

        /** 还原第 a 个锚的框到原图坐标。 */
        fun detectionAt(
            a: Int,
            score: Double,
            cls: Int,
        ): VisualDetection {
            val cx = raw[a].toDouble()
            val cy = raw[anchors + a].toDouble()
            val w = raw[2 * anchors + a].toDouble()
            val h = raw[(BOX_CHANNELS - 1) * anchors + a].toDouble()
            val l = ((cx - w / 2 - input.padX) / input.scale).toInt()
            val t = ((cy - h / 2 - input.padY) / input.scale).toInt()
            val r = ((cx + w / 2 - input.padX) / input.scale).toInt()
            val b = ((cy + h / 2 - input.padY) / input.scale).toInt()
            val box = Rect(l, t, r, b).intersect(0, 0, image.width, image.height)
            val category = classNames?.getOrNull(cls) ?: VisualCategory.ICON_CLICKABLE
            return VisualDetection(category, score, box)
        }
        val boxes = mutableListOf<VisualDetection>()
        for (a in 0 until anchors) {
            val (score, cls) = bestScore(raw, anchors, a, classes)
            if (score < minConfidence) continue
            boxes += detectionAt(a, score, cls)
        }
        return nms(boxes)
    }

    /** 第 a 个锚的最佳类别（argmax）及其得分。 */
    private fun bestScore(
        raw: FloatArray,
        anchors: Int,
        a: Int,
        classes: Int,
    ): Pair<Double, Int> {
        var best = 0.0
        var bestClass = 0
        for (c in 0 until classes) {
            val v = raw[(BOX_CHANNELS + c) * anchors + a].toDouble()
            if (v > best) {
                best = v
                bestClass = c
            }
        }
        return best to bestClass
    }

    /** 贪心 NMS：得分降序，同类框 IoU > [NMS_IOU] 的低分框剔除（单类时退化为类无关）。 */
    fun nms(boxes: List<VisualDetection>): List<VisualDetection> {
        val kept = mutableListOf<VisualDetection>()
        for (box in boxes.sortedByDescending { it.confidence }) {
            val clash = kept.any { it.category == box.category && it.bounds.iou(box.bounds) > NMS_IOU }
            if (!clash) kept += box
        }
        return kept
    }
}
