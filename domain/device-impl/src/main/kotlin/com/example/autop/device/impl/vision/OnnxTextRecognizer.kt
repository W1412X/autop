/**
 * ONNX 文本识别器（vp9 §4.4）：PP-OCRv5 mobile rec，单框裁剪识别一行文字（CTC 贪心解码）。
 * 字典类数校验（vp9 §4.1）：rec 输出类数＝字典行数（含末尾空格行）＋1，不符即停用并记 error。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer

/** PP-OCR rec 识别器。 */
internal class OnnxTextRecognizer(
    private val store: VisionModelStore,
) : TextRecognizer {
    private val charset: List<String> by lazy {
        store.text(KEYS)?.lines()?.dropLastWhile { it.isEmpty() } ?: emptyList()
    }

    /** 类数校验失败（字典与模型不匹配）后保持 false，不再尝试。 */
    private var charsetOk: Boolean = true

    override val ready: Boolean
        get() = charsetOk && charset.isNotEmpty() && store.session(REC) != null

    override fun recognize(
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine? {
        val session = store.session(REC).takeIf { ready } ?: return null
        return runCatching { runRec(session, image, box) }.getOrDefault(null)
    }

    /** rec 会话：单文本行 → 文字；空文本丢弃。 */
    private fun runRec(
        session: OrtSession,
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine? {
        val input = PpOcrCodec.recInput(image, box.bounds)
        val env = OrtEnvironment.getEnvironment()
        val shape =
            longArrayOf(1, COLOR_CHANNELS.toLong(), PpOcrCodec.REC_HEIGHT.toLong(), PpOcrCodec.REC_WIDTH.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val value = result.get(0).value as Array<Array<FloatArray>>
                val timesteps = value[0].size
                val classes = value[0][0].size
                if (!PpOcrCodec.charsetMatches(classes, charset)) {
                    charsetOk = false
                    Log.e(TAG, "rec 输出类数 $classes 与字典 ${charset.size}+1 不符，OCR 识别停用")
                    return null
                }
                val logits = FloatArray(timesteps * classes)
                value[0].forEachIndexed { t, row -> row.copyInto(logits, t * classes) }
                val text = PpOcrCodec.ctcDecode(logits, timesteps, classes, charset)
                return if (text.isEmpty()) null else VisualOcrLine(text, box.score, box.bounds)
            }
        }
    }

    private companion object {
        const val REC = "ocr_rec.onnx"
        const val KEYS = "ppocr_keys.txt"
        const val TAG = "OnnxTextRecognizer"
    }
}
