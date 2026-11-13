/**
 * ONNX 文字行定位引擎（ADR-0026）：PP-OCR DB mobile det，输入为叶子裁剪（原分辨率，长边 ≤ 960），
 * 输出概率图 → [TextDetCodec] 后处理成文字行框。前后处理在 [TextDetCodec]（纯 Kotlin）；
 * 模型缺失时 [ready]=false；推理异常向上抛。会话可被多个协程并发 run（ORT 支持同会话并发推理）。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.example.autop.device.Rect
import java.nio.FloatBuffer

/** PP-OCR DB 文字检测引擎。 */
internal class OnnxTextDetector(
    private val store: VisionModelStore,
) : TextDetector {
    override val ready: Boolean
        get() = store.session(MODEL) != null

    override fun locate(
        image: VisionImage,
        region: Rect,
    ): List<OcrBox> {
        val session = store.session(MODEL) ?: return emptyList()
        val input = TextDetCodec.input(image, region) ?: return emptyList()
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, COLOR_CHANNELS.toLong(), input.height.toLong(), input.width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input.chw), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                // 输出 [1,1,H,W] 概率图（DB 输出与输入同尺寸，仍按实际输出尺寸还原坐标）。
                val map = (result.get(0).value as Array<Array<Array<FloatArray>>>)[0][0]
                val mapHeight = map.size
                val mapWidth = map.firstOrNull()?.size ?: 0
                val prob = FloatArray(mapWidth * mapHeight)
                for (y in 0 until mapHeight) map[y].copyInto(prob, y * mapWidth)
                return TextDetCodec.boxes(prob, mapWidth, mapHeight, input)
            }
        }
    }

    internal companion object {
        /** PP-OCR DB mobile det（ADR-0026，4.75MB）。 */
        const val MODEL: String = "ocr_det.onnx"
    }
}
