/**
 * 视觉解析编排（ADR-0026）：YOLO 检测（只取框，标签为弱先验）→ 状态分类（勾选类）→ 建树分析
 * （吸收＋包含关系）→ 逐叶子采集 → 定稿伪树。
 *
 * 逐叶子采集是并行部分，依赖关系：
 * - 每个叶子内部：det（定位文字行）→ rec（逐行识别）严格串行，叶子之间互不依赖，没有阶段屏障
 *   （叶子 A 的 rec 可与叶子 B 的 det 同时进行）；
 * - 图标分类与文字支路没有数据依赖，叶子集合产出后立即并行启动。
 * 所有推理调用经 [InferenceGate] 共用一个并发上限（`device.vision.parallel.maxThreads`）。
 * 结果按叶子索引合并，与调度顺序无关；引擎端口注入，纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.example.autop.device.VisualTree
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 视觉解析 stages 产物：伪树＋最终检测框/OCR 行＋图标结果，供调试图渲染（[VisualDebugCapture]）。
 */
internal data class VisualParseResult(
    val tree: VisualTree,
    val detections: List<VisualDetection>,
    /** 叶子内定位出的全部文字行（逐行，未按叶子合并）。 */
    val lines: List<VisualOcrLine>,
    /** 图标结果（叶子框索引 → 显示名）。 */
    val iconResults: Map<Int, String> = emptyMap(),
    /** 图标裁图区域（叶子框索引 → 区域，调试图）。 */
    val iconRegions: Map<Int, Rect> = emptyMap(),
)

/** 解析参数（`device.vision.*` 配置映射）。 */
internal data class ParseOptions(
    val minConfidence: Double,
    val area: Rect? = null,
    /** input 提示语亮度差阈值（vp9 §4.6）。 */
    val hintContrast: Int = 60,
    val debug: Boolean = false,
    /** 树形约定违规回调（debug 抛出、release 记 warn）。 */
    val warn: (String) -> Unit = {},
)

/** 一次解析用到的全部引擎。 */
internal class VisionEngines(
    val detector: VisualDetector,
    val textDetector: TextDetector,
    val recognizer: TextRecognizer,
    val states: VisualStateClassifier,
    /** 图标分类器；关闭图标识别时为 null。 */
    val icons: VisualIconClassifier? = null,
) {
    /** 检测/文字定位/识别/图标（启用时）全部就绪才可解析（WP7.6：不可用就整体不可用）。 */
    val ready: Boolean
        get() = detector.ready && textDetector.ready && recognizer.ready && (icons?.ready ?: true)
}

/** 视觉解析管线。 */
internal object VisualParsePipeline {
    /** 同一叶子内多行文字的分隔（节点文字序列化时统一压成空格，与无障碍多行文本一致）。 */
    private const val LINE_BREAK: String = "\n"

    /**
     * 解析一帧；引擎未就绪返回 null。
     * 检测框截断：v2 多类档用 metadata `deploy_conf`，单类档用 [ParseOptions.minConfidence]。
     * [ParseOptions.area] 非 null 时丢弃内容区外的检测框。推理异常向上抛并取消同批其他任务。
     */
    suspend fun parse(
        image: VisionImage,
        engines: VisionEngines,
        options: ParseOptions,
        gate: InferenceGate,
    ): VisualParseResult? {
        if (!engines.ready) return null
        val floor = engines.detector.deployConf ?: options.minConfidence
        val kept = engines.detector.detect(image).filter { it.confidence >= floor && inArea(it.bounds, options.area) }
        val refined = categorize(engines.detector, kept, image)
        val finalDets = engines.states.classify(image, refined)
        val analysis = VisualTreeBuilder.analyze(finalDets)
        val collected = collectLeaves(image, analysis, engines, options, gate)
        val tree =
            VisualTreeBuilder.build(
                analysis,
                collected.leaves,
                image.width,
                image.height,
                options.debug,
                options.warn,
            )
        return VisualParseResult(tree, finalDets, collected.lines, collected.iconLabels, collected.iconRegions)
    }

    /** 采集产物：叶子内容＋文字行＋图标结果/裁图区域。 */
    private data class Collected(
        val leaves: Map<Int, VisualTreeBuilder.LeafInput>,
        val lines: List<VisualOcrLine>,
        val iconLabels: Map<Int, String>,
        val iconRegions: Map<Int, Rect>,
    )

    /**
     * 逐叶子采集：图标支路先启动；叶子按面积降序启动（大任务先跑，小任务补空隙）。
     * 任一子任务失败会取消其余并向上抛。
     */
    private suspend fun collectLeaves(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        engines: VisionEngines,
        options: ParseOptions,
        gate: InferenceGate,
    ): Collected =
        coroutineScope {
            val leafIdx = analysis.leaves()
            val candidates =
                leafIdx.map { i ->
                    i to IconCandidate(analysis.boxes[i].bounds, analysis.absorbed[i].iconRegion)
                }
            val iconJob = async { classifyIcons(image, candidates, engines.icons, gate) }
            val readJobs =
                leafIdx
                    .sortedByDescending { analysis.boxes[it].bounds.area }
                    .associateWith { i -> async { readLeaf(image, analysis.boxes[i].bounds, engines, gate) } }
            val reads = leafIdx.associateWith { readJobs.getValue(it).await() }
            val iconLabels = iconJob.await()
            val leaves =
                leafIdx.associateWith { i ->
                    val merged = merge(reads.getValue(i))
                    val iconLabel = iconLabels[i]
                    if (isHint(analysis.boxes[i], merged, image, options)) {
                        VisualTreeBuilder.LeafInput(hint = merged?.text, iconLabel = iconLabel)
                    } else {
                        VisualTreeBuilder.LeafInput(text = merged?.text, iconLabel = iconLabel)
                    }
                }
            Collected(
                leaves,
                leafIdx.flatMap { reads.getValue(it) },
                iconLabels,
                candidates.associate { (i, cand) -> i to (cand.iconRegion ?: cand.bounds) },
            )
        }

    /**
     * 读一个叶子：在叶子裁剪（与 rec 同样的外扩）内 det 定位文字行，只保留中心落在叶子框内的行
     * （外扩区会带入相邻控件的字），再逐行 rec；结果保持阅读序，识别为空的行丢弃。
     * det 无结果即该叶子没有文字（图标叶子不再被 rec 硬读出乱码）。
     */
    private suspend fun readLeaf(
        image: VisionImage,
        bounds: Rect,
        engines: VisionEngines,
        gate: InferenceGate,
    ): List<VisualOcrLine> =
        coroutineScope {
            val region = PpOcrCodec.recCrop(image, bounds)
            val located = gate.run { engines.textDetector.locate(image, region) }
            located
                .filter { it.bounds.centerIn(bounds) }
                .map { box -> async { gate.run { engines.recognizer.recognize(image, box) } } }
                .awaitAll()
                .filterNotNull()
        }

    /** 一个叶子的多行合并成一条：文字按行拼接，范围取并集，置信度按字数加权。 */
    private fun merge(lines: List<VisualOcrLine>): VisualOcrLine? {
        if (lines.size <= 1) return lines.firstOrNull()
        val total = lines.sumOf { it.text.length }
        val confidence = if (total == 0) 0.0 else lines.sumOf { it.confidence * it.text.length } / total
        val bounds =
            Rect(
                lines.minOf { it.bounds.l },
                lines.minOf { it.bounds.t },
                lines.maxOf { it.bounds.r },
                lines.maxOf { it.bounds.b },
            )
        return VisualOcrLine(lines.joinToString(LINE_BREAK) { it.text }, confidence, bounds)
    }

    /** 图标分类（每个叶子都进候选；拒识/未就绪不出结果）。整批一次推理，与文字支路并行。 */
    private suspend fun classifyIcons(
        image: VisionImage,
        candidates: List<Pair<Int, IconCandidate>>,
        icons: VisualIconClassifier?,
        gate: InferenceGate,
    ): Map<Int, String> {
        val classifier = icons?.takeIf { it.ready && candidates.isNotEmpty() } ?: return emptyMap()
        val results = gate.run { classifier.classify(image, candidates.map { it.second }) }
        return results.mapKeys { candidates[it.key].first }
    }

    /** input 叶提示语判定（vp9 §4.6）：亮度差 < hintContrast 的文字是 placeholder。 */
    private fun isHint(
        box: VisualDetection,
        line: VisualOcrLine?,
        image: VisionImage,
        options: ParseOptions,
    ): Boolean {
        if (line == null) return false
        if (box.category != VisualCategory.INPUT && box.category != VisualCategory.INPUT_FIELD) return false
        return InputHintDetector.isHint(image, line.bounds, options.hintContrast)
    }

    /** 单类兜底档经 SubtypeRefiner 细化（v2 多类直出不细化）；叶子文字在建树后，此处行传空。 */
    private fun categorize(
        detector: VisualDetector,
        kept: List<VisualDetection>,
        image: VisionImage,
    ): List<VisualDetection> =
        if (detector.singleClass) {
            SubtypeRefiner.refine(kept, emptyList(), image.width, image.height) { image.solidRatio(it) }
        } else {
            kept
        }

    private fun inArea(
        bounds: Rect,
        area: Rect?,
    ): Boolean = area == null || bounds.intersects(area)
}
