/**
 * 视觉解析编排（简化版）：YOLO 检测（只取框，标签为弱先验）→ 状态分类（勾选类）→ 建树分析
 * （吸收＋包含关系）→ 逐叶子采集（每个叶子无条件 OCR＋图标分类，input 叶判提示语）→ 定稿伪树。
 * 无独立文本检测/文本归属/补扫环节；引擎端口注入，纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.example.autop.device.VisualTree

/**
 * 视觉解析 stages 产物：伪树＋最终检测框/叶子 OCR 行＋图标结果，供调试图渲染（[VisualDebugCapture]）。
 */
internal data class VisualParseResult(
    val tree: VisualTree,
    val detections: List<VisualDetection>,
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

/** 视觉解析管线。 */
internal object VisualParsePipeline {
    /**
     * 解析一帧；检测/OCR/图标（启用时）未就绪返回 null（WP7.6：不可用就整体不可用，不返回缺少内容的树）。
     * 检测框截断：v2 多类档用 metadata `deploy_conf`，单类档用 [ParseOptions.minConfidence]。
     * [ParseOptions.area] 非 null 时丢弃内容区外的检测框。
     */
    fun parse(
        image: VisionImage,
        detector: VisualDetector,
        recognizer: TextRecognizer,
        states: VisualStateClassifier,
        options: ParseOptions,
        icons: VisualIconClassifier? = null,
    ): VisualParseResult? {
        if (!detector.ready || !recognizer.ready) return null
        if (icons != null && !icons.ready) return null
        val floor = detector.deployConf ?: options.minConfidence
        val kept = detector.detect(image).filter { it.confidence >= floor && inArea(it.bounds, options.area) }
        val refined = categorize(detector, kept, image)
        val finalDets = states.classify(image, refined)
        val analysis = VisualTreeBuilder.analyze(finalDets)
        val collected = collectLeaves(image, analysis, recognizer, icons, options)
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

    /** 采集产物：叶子内容＋OCR 行＋图标结果/裁图区域。 */
    private data class Collected(
        val leaves: Map<Int, VisualTreeBuilder.LeafInput>,
        val lines: List<VisualOcrLine>,
        val iconLabels: Map<Int, String>,
        val iconRegions: Map<Int, Rect>,
    )

    /** 逐叶子采集：每个叶子无条件 OCR＋图标分类（不看类别标签）；input 叶文字判提示语。 */
    private fun collectLeaves(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        recognizer: TextRecognizer,
        icons: VisualIconClassifier?,
        options: ParseOptions,
    ): Collected {
        val leafIdx = analysis.leaves()
        val lines = HashMap<Int, VisualOcrLine>()
        val candidates = ArrayList<Pair<Int, IconCandidate>>(leafIdx.size)
        for (i in leafIdx) {
            val box = analysis.boxes[i]
            recognizer.recognize(image, OcrBox(box.bounds, box.confidence))?.let { lines[i] = it }
            val iconRegion = analysis.absorbed[i].iconRegion
            candidates += i to IconCandidate(box.bounds, iconRegion)
        }
        val iconLabels = classifyIcons(image, candidates, icons)
        val iconRegions = candidates.associate { (i, cand) -> i to (cand.iconRegion ?: cand.bounds) }
        val leaves =
            leafIdx.associateWith { i ->
                val line = lines[i]
                val hint = isHint(analysis.boxes[i], line, image, options)
                val iconLabel = iconLabels[i]
                if (hint) {
                    VisualTreeBuilder.LeafInput(hint = line?.text, iconLabel = iconLabel)
                } else {
                    VisualTreeBuilder.LeafInput(text = line?.text, iconLabel = iconLabel)
                }
            }
        return Collected(leaves, lines.values.toList(), iconLabels, iconRegions)
    }

    /** 图标分类（每个叶子都进候选；拒识/未就绪不出结果）。 */
    private fun classifyIcons(
        image: VisionImage,
        candidates: List<Pair<Int, IconCandidate>>,
        icons: VisualIconClassifier?,
    ): Map<Int, String> {
        val classifier = icons?.takeIf { it.ready && candidates.isNotEmpty() } ?: return emptyMap()
        val results = classifier.classify(image, candidates.map { it.second })
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

    /** 单类兜底档经 SubtypeRefiner 细化（v2 多类直出不细化）；叶子 OCR 在建树后，此处行传空。 */
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
