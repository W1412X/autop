/**
 * ONNX OCR 引擎（vp9 §4.4）：PP-OCRv5 mobile det+rec 组合的兼容入口。
 * 检测/识别拆分为 [OnnxTextDetector]／[OnnxTextRecognizer]（补扫可单用 det 限定区域）；
 * 前后处理在 [PpOcrCodec]（BGR＋ImageNet 归一化、box_thresh、CTC）。
 */
package com.example.autop.device.impl.vision

/** PP-OCR ONNX 识别引擎（组合式）。 */
internal class OnnxVisualOcrEngine(
    store: VisionModelStore,
) : VisualOcrEngine {
    private val detector = OnnxTextDetector(store)
    private val recognizer = OnnxTextRecognizer(store)

    override val ready: Boolean
        get() = detector.ready && recognizer.ready

    override fun recognize(image: VisionImage): List<VisualOcrLine> {
        if (!ready) return emptyList()
        return detector.detect(image).mapNotNull { recognizer.recognize(image, it) }
    }
}
