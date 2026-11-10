/**
 * ONNX 控件检测引擎（docs/design/visual-screen-parsing §4；vp8c ADR-0008 决策 2/3）：
 * v2 多类直出（metadata `names` 映射类别）或 GPA 单类兜底档（SubtypeRefiner 细化）；
 * labels_version 不认识时拒绝加载（ready=false）。前后处理在 [YoloDecoder]（纯 Kotlin）；
 * 模型缺失时 [ready]=false；推理异常向上抛（WP7.6：不伪装成空树，取消异常原样抛）。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** YOLO ONNX 检测引擎。 */
internal class OnnxVisualDetector(
    private val store: VisionModelStore,
) : VisualDetector {
    /** 档位裁决（会话 metadata 惰性解析，进程内不变）。 */
    private val tier: DetectorTier by lazy { tierOf(store.session(MODEL)) }

    override val ready: Boolean
        get() = store.session(MODEL) != null && tier != DetectorTier.Reject

    override val singleClass: Boolean
        get() = tier == DetectorTier.Single

    override val deployConf: Double?
        get() = (tier as? DetectorTier.Multi)?.meta?.deployConf

    override fun detect(image: VisionImage): List<VisualDetection> {
        val session = store.session(MODEL) ?: return emptyList()
        return infer(session, image)
    }

    /** metadata → 档位（会话加载失败按拒绝处理）。 */
    private fun tierOf(session: OrtSession?): DetectorTier =
        if (session == null) {
            DetectorTier.Reject
        } else {
            detectorTierOf(session.metadata.customMetadata)
        }

    /** 会话推理：letterbox → run → 解码+NMS（多类档带 metadata 类名表）。 */
    private fun infer(
        session: OrtSession,
        image: VisionImage,
    ): List<VisualDetection> {
        val input = YoloDecoder.letterbox(image)
        val env = OrtEnvironment.getEnvironment()
        val imgSize = YoloDecoder.IMG_SIZE.toLong()
        val shape = longArrayOf(1, COLOR_CHANNELS.toLong(), imgSize, imgSize)
        val names = (tier as? DetectorTier.Multi)?.meta?.classNames
        val floor = deployConf ?: DET_FLOOR
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input.chw), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val raw = flatten(result.get(0).value as Array<Array<FloatArray>>)
                val anchors = raw.second
                return YoloDecoder.decode(raw.first, anchors, input, floor, image, names)
            }
        }
    }

    /** `[1][C][A]` → 平铺 `[C*A]`＋锚数 A。 */
    private fun flatten(value: Array<Array<FloatArray>>): Pair<FloatArray, Int> {
        val channels = value[0].size
        val anchors = value[0][0].size
        val raw = FloatArray(channels * anchors)
        for (c in 0 until channels) {
            value[0][c].copyInto(raw, c * anchors)
        }
        return raw to anchors
    }

    private companion object {
        const val MODEL = "gui_detect.onnx"

        /** 单类档内部噪声下限（真正的截断阈值由管线按配置施加）。 */
        const val DET_FLOOR: Double = 0.05
    }
}
