/**
 * ONNX OCR 引擎（vp9 §4.4）：PP-OCRv5 det+rec 组合的兼容入口。
 * 检测/识别拆分为 [OnnxTextDetector]／[TextRecognizer]（补扫可单用 det 限定区域）；
 * 识别器可注入 [TieredTextRecognizer] 双档级联（ocr-accuracy-uplift 阶段 B）；
 * 前后处理在 [PpOcrCodec]（BGR＋ImageNet 归一化、box_thresh、CTC）。
 */
package com.example.autop.device.impl.vision

/** PP-OCR ONNX 识别引擎（组合式）。 */
internal class OnnxVisualOcrEngine(
    store: VisionModelStore,
    private val recognizer: TextRecognizer = OnnxTextRecognizer(store),
) : VisualOcrEngine {
    private val detector = OnnxTextDetector(store)

    override val ready: Boolean
        get() = detector.ready && recognizer.ready

    override fun recognize(image: VisionImage): List<VisualOcrLine> = recognize(image, RecCascadeOptions())

    override fun recognize(
        image: VisionImage,
        cascade: RecCascadeOptions,
    ): List<VisualOcrLine> {
        if (!ready) return emptyList()
        return recognizer.recognizeAll(image, detector.detect(image), cascade).mapNotNull { it }
    }
}
