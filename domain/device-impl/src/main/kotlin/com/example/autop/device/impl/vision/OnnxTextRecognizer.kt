/**
 * ONNX 文本识别器（vp9 §4.4）：PP-OCRv5 rec，单框裁剪识别一行文字。
 * 解码走 [CtcDecoder]（beam search＋词典重打分，ocr-accuracy-uplift 阶段 A）；
 * 行置信度＝CTC 贪心路径逐字概率均值（阶段 B 级联选行依据，替代 det 框分）。
 * 字典类数校验（vp9 §4.1）：rec 输出类数＝字典行数（含末尾空格行）＋1，不符即停用并记 error。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.example.autop.device.Rect
import java.nio.FloatBuffer

/** PP-OCR rec 识别器（单模型：PP-OCRv5 server rec，逐叶子裁剪识别）。 */
internal class OnnxTextRecognizer(
    private val store: VisionModelStore,
) : TextRecognizer {
    private val charset: List<String> by lazy {
        store.text(KEYS)?.lines()?.dropLastWhile { it.isEmpty() } ?: emptyList()
    }
    private val lexicon: Set<String> by lazy { CtcDecoder.lexiconOf(store.text(LEXICON)) }

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

    /** rec 会话：裁剪框切窗批量识别 → 文本拼接（长行切窗，消除 320 宽截断）；空文本丢弃。 */
    private fun runRec(
        session: OrtSession,
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine? {
        val windows = PpOcrCodec.recWindows(PpOcrCodec.recCrop(image, box.bounds))
        val parts = runBatch(session, image, windows)
        val text = CtcDecoder.stitch(parts.map { it.text })
        if (text.isEmpty()) return null
        val totalLen = parts.sumOf { it.text.length }
        val confidence = if (totalLen == 0) 0.0 else parts.sumOf { it.confidence * it.text.length } / totalLen
        return VisualOcrLine(text, confidence, box.bounds)
    }

    /** 切窗批量推理（一次 session.run）→ 逐窗 CTC 解码。 */
    private fun runBatch(
        session: OrtSession,
        image: VisionImage,
        windows: List<Rect>,
    ): List<CtcResult> {
        val plane = COLOR_CHANNELS * PpOcrCodec.REC_HEIGHT * PpOcrCodec.REC_WIDTH
        val batch = FloatArray(windows.size * plane)
        windows.forEachIndexed { i, crop -> PpOcrCodec.recInput(image, crop).copyInto(batch, i * plane) }
        val env = OrtEnvironment.getEnvironment()
        val shape =
            longArrayOf(
                windows.size.toLong(),
                COLOR_CHANNELS.toLong(),
                PpOcrCodec.REC_HEIGHT.toLong(),
                PpOcrCodec.REC_WIDTH.toLong(),
            )
        OnnxTensor.createTensor(env, FloatBuffer.wrap(batch), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val value = result.get(0).value as Array<Array<FloatArray>>
                val classes = value[0][0].size
                if (!PpOcrCodec.charsetMatches(classes, charset)) {
                    charsetOk = false
                    Log.e(TAG, "rec 输出类数 $classes 与字典 ${charset.size}+1 不符，OCR 识别停用")
                    return emptyList()
                }
                return value.map { decodeWindow(it) }
            }
        }
    }

    /** 单窗输出 → CTC 解码（beam＋词典）。 */
    private fun decodeWindow(rows: Array<FloatArray>): CtcResult {
        val timesteps = rows.size
        val classes = rows[0].size
        val logits = FloatArray(timesteps * classes)
        rows.forEachIndexed { t, row -> row.copyInto(logits, t * classes) }
        return CtcDecoder.decode(logits, timesteps, classes, charset, CtcDecodeOptions(lexicon = lexicon))
    }

    internal companion object {
        /** PP-OCRv5 server rec（最优档，量化 conv_sym 27.79MB；692 样本与 fp32 解码一致）。 */
        const val REC: String = "ocr_rec_server.onnx"

        /** UI 域词典（beam 重打分用，ocr-accuracy-uplift 阶段 A）。 */
        const val LEXICON: String = "ocr_lexicon.txt"

        private const val KEYS = "ppocr_keys.txt"
        private const val TAG = "OnnxTextRecognizer"
    }
}
