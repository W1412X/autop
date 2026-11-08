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
import java.nio.FloatBuffer

/** PP-OCR rec 识别器（mobile/server 共用实现，[recAsset] 切换权重档位）。 */
internal class OnnxTextRecognizer(
    private val store: VisionModelStore,
    private val recAsset: String = REC,
) : TextRecognizer {
    private val charset: List<String> by lazy {
        store.text(KEYS)?.lines()?.dropLastWhile { it.isEmpty() } ?: emptyList()
    }
    private val lexicon: Set<String> by lazy { CtcDecoder.lexiconOf(store.text(LEXICON)) }

    /** 类数校验失败（字典与模型不匹配）后保持 false，不再尝试。 */
    private var charsetOk: Boolean = true

    override val ready: Boolean
        get() = charsetOk && charset.isNotEmpty() && store.session(recAsset) != null

    override fun recognize(
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine? {
        val session = store.session(recAsset).takeIf { ready } ?: return null
        return runCatching { runRec(session, image, box) }.getOrDefault(null)
    }

    /** rec 会话：单文本行 → 文字＋CTC 行分；空文本丢弃。 */
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
                    Log.e(TAG, "rec($recAsset) 输出类数 $classes 与字典 ${charset.size}+1 不符，OCR 识别停用")
                    return null
                }
                val logits = FloatArray(timesteps * classes)
                value[0].forEachIndexed { t, row -> row.copyInto(logits, t * classes) }
                val decoded =
                    CtcDecoder.decode(logits, timesteps, classes, charset, CtcDecodeOptions(lexicon = lexicon))
                return if (decoded.text.isEmpty()) {
                    null
                } else {
                    VisualOcrLine(decoded.text, decoded.confidence, box.bounds)
                }
            }
        }
    }

    internal companion object {
        /** PP-OCRv5 mobile rec（现用快档）。 */
        const val REC: String = "ocr_rec.onnx"

        /** PP-OCRv5 server rec（精档，ocr-accuracy-uplift 阶段 B；资产缺失自动退 mobile）。 */
        const val REC_SERVER: String = "ocr_rec_server.onnx"

        /** UI 域词典（beam 重打分用，ocr-accuracy-uplift 阶段 A）。 */
        const val LEXICON: String = "ocr_lexicon.txt"

        private const val KEYS = "ppocr_keys.txt"
        private const val TAG = "OnnxTextRecognizer"
    }
}
