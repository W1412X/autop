/**
 * ONNX 文本检测器（vp9 §4.4）：PP-OCRv5 mobile det，任意画布（全图/补扫块）上找文本行框。
 * 预处理/后处理在 [PpOcrCodec]（BGR＋ImageNet 归一化、DB 连通域＋box_thresh）。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.example.autop.device.Rect
import java.nio.FloatBuffer

/** PP-OCR det 检测器。 */
internal class OnnxTextDetector(
    private val store: VisionModelStore,
) : TextDetector {
    override val ready: Boolean
        get() = store.session(DET) != null

    override fun detect(
        image: VisionImage,
        region: Rect?,
    ): List<OcrBox> {
        if (!ready) return emptyList()
        val boxes = runCatching { runDet(image)?.let(PpOcrCodec::detBoxes).orEmpty() }.getOrDefault(emptyList())
        return if (region == null) boxes else boxes.filter { inRegion(it.bounds, region) }
    }

    /** det 会话：概率图 → [PpOcrCodec.DetMap]。 */
    private fun runDet(image: VisionImage): PpOcrCodec.DetMap? {
        val session = store.session(DET) ?: return null
        val (mapW, mapH) = PpOcrCodec.detSize(image.width, image.height)
        val input = PpOcrCodec.detInput(image, mapW, mapH)
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, COLOR_CHANNELS.toLong(), mapH.toLong(), mapW.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val prob = flatten(result.get(0).value)
                return PpOcrCodec.DetMap(prob, mapW, mapH, image.width.toDouble() / mapW)
            }
        }
    }

    /** 框心落在 [region] 内（vp9 §4.4 detect 的限定范围）。 */
    private fun inRegion(
        bounds: Rect,
        region: Rect,
    ): Boolean = bounds.centerX in region.l..region.r && bounds.centerY in region.t..region.b

    /** 任意嵌套维度浮点输出 → 平铺（det 是 [1][1][H][W]）。 */
    private fun flatten(value: Any): FloatArray {
        val out = mutableListOf<Float>()
        collect(value, out)
        return out.toFloatArray()
    }

    private fun collect(
        value: Any,
        out: MutableList<Float>,
    ) {
        when (value) {
            is Array<*> -> value.forEach { if (it != null) collect(it, out) }
            is FloatArray -> out.addAll(value.asList())
            else -> Unit
        }
    }

    private companion object {
        const val DET = "ocr_det.onnx"
    }
}
