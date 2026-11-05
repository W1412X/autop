/**
 * ONNX 开关状态分类引擎（vp8 §6.3，state_cls_v2/mobilenet_v3_small）：
 * 勾选类框（checkbox/radio/switch）crop → 三态 checked。裁剪规格在 [StateCropCodec]
 * （与 PoC 严格一致）；state_conf 读 ONNX metadata（默认 0.8）。推理失败按未知态处理。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import kotlin.math.exp

/** ONNX 状态分类引擎。 */
internal class OnnxStateClassifier(
    private val store: VisionModelStore,
) : VisualStateClassifier {
    /** 三态阈值（metadata `state_conf` 惰性解析）。 */
    private val stateConf: Double by lazy { confFromMeta() }

    override val ready: Boolean
        get() = store.session(MODEL) != null

    override fun classify(
        image: VisionImage,
        detections: List<VisualDetection>,
    ): List<VisualDetection> {
        if (!ready) return detections
        return detections.map { det ->
            if (det.category !in CHECKABLE) {
                det
            } else {
                det.copy(checked = stateOf(image, det))
            }
        }
    }

    /** 勾选类框 → 三态（on/off/未知=置信不足，不猜）。 */
    private fun stateOf(
        image: VisionImage,
        det: VisualDetection,
    ): Boolean? {
        val pOn = runCatching { predict(image, det) }.getOrNull() ?: return null
        return when {
            pOn >= stateConf -> true
            pOn <= 1 - stateConf -> false
            else -> null
        }
    }

    /** 单框推理：crop → [1,3,48,144] → 概率 p_on（输出 [1,2] 为 logits，softmax 取 on 列）。 */
    private fun predict(
        image: VisionImage,
        det: VisualDetection,
    ): Double {
        val session = store.session(MODEL) ?: return UNKNOWN_PROB
        val input = StateCropCodec.crop(image, det.bounds)
        val env = OrtEnvironment.getEnvironment()
        val shape =
            longArrayOf(1, COLOR_CHANNELS.toLong(), StateCropCodec.CROP_H.toLong(), StateCropCodec.CROP_W.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result.get(0).value as Array<FloatArray>
                return softmaxOn(out[0])
            }
        }
    }

    /** 二类 logits → p_on（与 PoC `StateClassifier.predict` 同口径）。 */
    private fun softmaxOn(logits: FloatArray): Double {
        if (logits.size < 2) return UNKNOWN_PROB
        val max = maxOf(logits[0], logits[1])
        val e0 = exp((logits[0] - max).toDouble())
        val e1 = exp((logits[1] - max).toDouble())
        return e1 / (e0 + e1)
    }

    /** metadata `state_conf`（缺失/非法取默认 0.8）。 */
    private fun confFromMeta(): Double {
        val session = store.session(MODEL) ?: return DEFAULT_STATE_CONF
        val raw = session.metadata.customMetadata["state_conf"]
        return raw?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: DEFAULT_STATE_CONF
    }

    private companion object {
        const val MODEL = "state_cls.onnx"
        const val DEFAULT_STATE_CONF: Double = 0.8

        /** 置信不足/推理失败的中性概率（p_on=0.5 → 未知态）。 */
        const val UNKNOWN_PROB: Double = 0.5

        /** 状态分类适用类别（v1/v2 同名，vp8 §6.3）。 */
        val CHECKABLE: Set<String> = setOf(VisualCategory.CHECKBOX, VisualCategory.RADIO, VisualCategory.SWITCH)
    }
}
