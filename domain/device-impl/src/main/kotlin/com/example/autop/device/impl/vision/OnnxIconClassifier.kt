/**
 * ONNX 图标分类引擎（vp9 §5.3，icon_cls/mobilenet_v3_small 102 类）：候选叶子
 * （吸收所得 iconRegion 优先，否则整框）→ [IconClsWire] letterbox → logits softmax＋拒识 →
 * [IconLabelTable] 显示键。metadata（labels/icon_conf/icon_margin/reject_labels）只读模型部署参数；
 * 校验失败 `ready=false`，所有 `icon=""`，其余链路照常。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import com.example.autop.device.Rect
import java.nio.FloatBuffer

/** ONNX 图标分类引擎。 */
internal class OnnxIconClassifier(
    private val store: VisionModelStore,
    private val table: IconLabelTable,
) : VisualIconClassifier {
    private val meta: Map<String, String> by lazy {
        val custom = store.session(MODEL)?.metadata?.customMetadata
        custom.orEmpty()
    }

    private val labels: List<String> by lazy { parseList(meta["labels"]) }

    private val reject: Set<String> by lazy {
        meta["reject_labels"]?.let { parseList(it).toSet() } ?: table.reject
    }

    private val iconConf: Double by lazy { meta["icon_conf"]?.toDoubleOrNull() ?: IconClsWire.DEFAULT_ICON_CONF }

    private val iconMargin: Double by lazy {
        meta["icon_margin"]?.toDoubleOrNull() ?: IconClsWire.DEFAULT_ICON_MARGIN
    }

    private val metaOk: Boolean by lazy { IconClsWire.metadataOk(meta) }

    override val ready: Boolean
        get() = metaOk && labels.isNotEmpty() && store.session(MODEL) != null

    override fun classify(
        image: VisionImage,
        candidates: List<IconCandidate>,
    ): Map<Int, String> {
        if (!ready) return emptyMap()
        val regions = candidates.mapIndexed { i, cand -> i to (cand.iconRegion ?: cand.bounds) }
        return if (regions.isEmpty()) {
            emptyMap()
        } else {
            val keys = predictAll(image, regions.map { it.second })
            val out = LinkedHashMap<Int, String>()
            for (slot in regions.indices) {
                val key = keys[slot]
                if (key != null) out[regions[slot].first] = table.displayOf(key)
            }
            out
        }
    }

    /** 全候选推理（输入第 0 维动态时拼 batch，否则逐张，§5.3）。 */
    private fun predictAll(
        image: VisionImage,
        regions: List<Rect>,
    ): List<String?> {
        val session = store.session(MODEL) ?: return List(regions.size) { null }
        val plane = COLOR_CHANNELS * IconClsWire.INPUT_SIZE * IconClsWire.INPUT_SIZE
        val inputs = FloatArray(regions.size * plane)
        regions.forEachIndexed { slot, region ->
            IconClsWire.letterbox(image, region).copyInto(inputs, slot * plane)
        }
        val inputInfos = session.inputInfo.values
        return if (batched(inputInfos.firstOrNull()?.info)) {
            runOnnx(session, inputs, regions.size)
        } else {
            regions.indices.map { slot -> runOnnx(session, inputs, 1, slot * plane).firstOrNull() }
        }
    }

    /** 输入第 0 维是否动态。 */
    private fun batched(type: ai.onnxruntime.ValueInfo?): Boolean {
        val shape = (type as? TensorInfo)?.shape ?: return false
        return shape.isNotEmpty() && shape[0] < 0
    }

    /** 批推理：`[N,3,96,96]` → N 个显示键（拒识为 null）。 */
    private fun runOnnx(
        session: ai.onnxruntime.OrtSession,
        inputs: FloatArray,
        count: Int,
        offset: Int = 0,
    ): List<String?> {
        val plane = COLOR_CHANNELS * IconClsWire.INPUT_SIZE * IconClsWire.INPUT_SIZE
        val view =
            if (offset == 0 && count * plane == inputs.size) {
                inputs
            } else {
                inputs.copyOfRange(offset, offset + count * plane)
            }
        val env = OrtEnvironment.getEnvironment()
        val shape =
            longArrayOf(
                count.toLong(),
                COLOR_CHANNELS.toLong(),
                IconClsWire.INPUT_SIZE.toLong(),
                IconClsWire.INPUT_SIZE.toLong(),
            )
        OnnxTensor.createTensor(env, FloatBuffer.wrap(view), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result.get(0).value as Array<FloatArray>
                return out.map { IconClsWire.decode(it, labels, reject, iconConf, iconMargin) }
            }
        }
    }

    /** `["a", "b"]` → 列表（宽松解析，labels/reject 均为标识符集合）。 */
    private fun parseList(raw: String?): List<String> =
        raw
            ?.trim()
            ?.removeSurrounding("[", "]")
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private companion object {
        const val MODEL = "icon_cls.onnx"
    }
}
