/**
 * 视觉解析编排（vp9 §4.2 新顺序）：检测 → 全图 det＋rec → 状态分类 → 骨架＋原子吸收 →
 * 文字归属（§3.2）→ 图标推理（§5）→ 补扫规划（§4.3）→ 补扫 det＋rec（§4.4）→ 补扫合并
 * （§4.5）→ 不变量检查（§3.4）→ 定稿伪树。引擎端口注入，纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.example.autop.device.VisualTree

/**
 * 视觉解析 stages 产物（N208＋vp9）：伪树＋最终检测框/OCR 行＋补扫框＋图标结果，
 * 供调试图渲染（[VisualDebugCapture]）。
 */
internal data class VisualParseResult(
    val tree: VisualTree,
    val detections: List<VisualDetection>,
    val lines: List<VisualOcrLine>,
    /** 补扫裁剪区（屏幕坐标，调试图黄框，vp9 §4.3）。 */
    val rescanRegions: List<Rect> = emptyList(),
    /** 图标结果（吸收后框索引 → 显示名，vp9 §5）。 */
    val iconResults: Map<Int, String> = emptyMap(),
    /** 图标裁图区域（框索引 → 区域，vp9 §5.2，调试图）。 */
    val iconRegions: Map<Int, Rect> = emptyMap(),
    /** 补扫拼图画布（§4.4，调试图；mosaic 关闭/无补扫为 null）。 */
    val mosaicCanvas: VisionImage? = null,
)

/** 解析参数（`device.vision.*` 配置映射，vp9 §7）。 */
internal data class ParseOptions(
    val minConfidence: Double,
    val area: Rect? = null,
    val iconCategories: Set<String> = emptySet(),
    val minIconSide: Double = 0.6,
    val rescanEnabled: Boolean = true,
    val maxBoxes: Int = 6,
    val padRatio: Double = 0.1,
    val targetTextPx: Int = 32,
    val maxScale: Double = 3.0,
    val lowConf: Double = 0.6,
    val dupIou: Double = 0.5,
    val spanOverlap: Double = 0.3,
    val mosaic: Boolean = true,
    val mosaicGap: Int = 24,
    val hintContrast: Int = 60,
    val debug: Boolean = false,
    /** rec 双档级联选项（ocr-accuracy-uplift 阶段 B）。 */
    val recCascade: RecCascadeOptions = RecCascadeOptions(),
    /** 不变量违规回调（vp9 §3.4：debug 抛出、release 记 warn）。 */
    val warn: (String) -> Unit = {},
)

/** 可选引擎（图标分类与补扫 det/rec，vp9 §4.4/§5）。 */
internal data class OptionalEngines(
    val icons: VisualIconClassifier? = null,
    val rescan: RescanEngines? = null,
)

/** 补扫引擎（拆分后的 det/rec，vp9 §4.4）。 */
internal data class RescanEngines(
    val detector: TextDetector,
    val recognizer: TextRecognizer,
    val cache: RescanCache? = null,
)

/** 视觉解析管线。 */
internal object VisualParsePipeline {
    /**
     * 解析一帧（vp9 §4.2）；检测/OCR 未就绪返回 null（编排层回退现有行为）。
     * 检测框截断：v2 多类档用 metadata `deploy_conf`，单类档用 [ParseOptions.minConfidence]。
     * N204：[ParseOptions.area] 非 null 时丢弃内容区外的检测框与 OCR 行。
     */
    fun parse(
        image: VisionImage,
        detector: VisualDetector,
        ocr: VisualOcrEngine,
        states: VisualStateClassifier,
        options: ParseOptions,
        engines: OptionalEngines = OptionalEngines(),
    ): VisualParseResult? {
        if (!detector.ready || !ocr.ready) return null
        val floor = detector.deployConf ?: options.minConfidence
        val kept = detector.detect(image).filter { it.confidence >= floor && inArea(it.bounds, options.area) }
        val lines = ocr.recognize(image, options.recCascade).filter { inArea(it.bounds, options.area) }
        val refined = categorize(detector, kept, lines, image)
        val finalDets = states.classify(image, refined)
        val analysis = VisualTreeBuilder.analyze(finalDets, lines, options.spanOverlap)
        val iconPass = classifyIcons(image, analysis, lines, engines, options)
        val outcome = rescanPass(image, analysis, lines, engines.rescan, iconPass.labels, options)
        val hintLines = inputHints(image, analysis, outcome, options)
        val tree =
            VisualTreeBuilder.build(
                VisualTreeBuilder.FinalInput(
                    analysis,
                    outcome.lines,
                    outcome.ownership,
                    iconPass.labels,
                    options.dupIou,
                    hintLines,
                ),
                image.width,
                image.height,
                options.debug,
                options.warn,
            )
        return VisualParseResult(
            tree,
            finalDets,
            outcome.lines,
            outcome.regions,
            iconPass.labels,
            iconPass.regions,
            outcome.mosaic,
        )
    }

    /** input 提示语判定（vp9 §4.6）：input 类叶子归属行亮度差 < hintContrast 进 hint。 */
    private fun inputHints(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        outcome: Outcome,
        options: ParseOptions,
    ): Map<Int, List<Int>> {
        val out = HashMap<Int, MutableList<Int>>()
        for ((boxIdx, lineIdxs) in outcome.ownership.leafLines) {
            val category = analysis.boxes[boxIdx].category
            if (category != VisualCategory.INPUT && category != VisualCategory.INPUT_FIELD) continue
            for (i in lineIdxs) {
                val line = outcome.lines.getOrNull(i) ?: continue
                if (InputHintDetector.isHint(image, line.bounds, options.hintContrast)) {
                    out.getOrPut(boxIdx) { mutableListOf() } += i
                }
            }
        }
        return out
    }

    /** 图标推理产物：显示名＋裁图区域（调试图）。 */
    private data class IconPass(
        val labels: Map<Int, String>,
        val regions: Map<Int, Rect>,
    )

    /** 图标推理（vp9 §5.1/§5.2）：categories 内的叶子候选裁图分类，缓存命中直接采用。 */
    private fun classifyIcons(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        lines: List<VisualOcrLine>,
        engines: OptionalEngines,
        options: ParseOptions,
    ): IconPass {
        val classifier = engines.icons?.takeIf { it.ready && options.iconCategories.isNotEmpty() }
        val candidates = if (classifier == null) emptyList() else iconCandidates(analysis, lines, options)
        if (candidates.isEmpty() || classifier == null) return IconPass(emptyMap(), emptyMap())
        val cache = engines.rescan?.cache
        val out = HashMap<Int, String>()
        val toRun = mutableListOf<Pair<Int, IconCandidate>>()
        for ((i, cand) in candidates) {
            val key = iconKey(image, analysis.boxes[i], cand)
            val hit = cache?.iconOf(key)
            if (hit != null) {
                out[i] = hit
            } else {
                toRun += i to cand
            }
        }
        if (toRun.isNotEmpty()) {
            val results = classifier.classify(image, toRun.map { it.second })
            for ((slot, label) in results) {
                val boxIdx = toRun[slot].first
                out[boxIdx] = label
                cache?.putIcon(iconKey(image, analysis.boxes[boxIdx], toRun[slot].second), label)
            }
        }
        val regions =
            candidates.associate { (i, cand) ->
                val fallback = IconRegion.cropRegion(cand.bounds, cand.iconRegion, cand.textLines, options.minIconSide)
                i to (cand.iconRegion ?: fallback ?: cand.bounds)
            }
        return IconPass(out, regions)
    }

    /** 图标候选（vp9 §5.1）：categories 内的叶子，带吸收 iconRegion 与归属文字行。 */
    private fun iconCandidates(
        analysis: VisualTreeBuilder.Analysis,
        lines: List<VisualOcrLine>,
        options: ParseOptions,
    ): List<Pair<Int, IconCandidate>> {
        val candidates = mutableListOf<Pair<Int, IconCandidate>>()
        val usable =
            analysis.absorbed.withIndex().filter { (i, a) ->
                i !in analysis.ownership.drops &&
                    a.detection.category in options.iconCategories &&
                    analysis.kids[i].isNullOrEmpty()
            }
        for ((i, a) in usable) {
            val lineIdxs = analysis.ownership.leafLines[i].orEmpty()
            val textLines = lineIdxs.mapNotNull { lines.getOrNull(it)?.bounds }
            candidates += i to IconCandidate(a.detection.bounds, a.iconRegion, textLines)
        }
        return candidates
    }

    /** 补扫（vp9 §4.3–§4.5）：规划 → 缓存/裁剪拼图 det＋rec → 合并。 */
    private fun rescanPass(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        lines: List<VisualOcrLine>,
        engines: RescanEngines?,
        iconOf: Map<Int, String>,
        options: ParseOptions,
    ): Outcome {
        val idle = Outcome(lines, analysis.ownership, emptyList(), null)
        val enabled = engines != null && options.rescanEnabled && engines.detector.ready && engines.recognizer.ready
        return if (enabled) rescanRun(image, analysis, lines, engines, iconOf, options) else idle
    }

    /** 补扫执行（规划→缓存→det/rec→合并）。 */
    private fun rescanRun(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        lines: List<VisualOcrLine>,
        engines: RescanEngines,
        iconOf: Map<Int, String>,
        options: ParseOptions,
    ): Outcome {
        val ownership = analysis.ownership
        val idle = Outcome(lines, ownership, emptyList(), null)
        val leafLines = ownership.leafLines
        val lineConf = leafLines.mapValues { (_, idxs) -> idxs.mapNotNull { lines.getOrNull(it)?.confidence } }
        val spanLeaves = ownership.spans.flatMap { it.leaves }.distinct()
        val covered = ownership.drops.toSet()
        val base =
            RescanPlanner.Context(
                kids = analysis.kids,
                leafLines = leafLines,
                iconOf = iconOf,
                spanLeaves = spanLeaves,
                lineConf = lineConf,
                coveredText = covered,
            )
        val all = RescanPlanner.plan(analysis.boxes, base, options.lowConf, Int.MAX_VALUE)
        if (all.isEmpty()) return idle
        val allCrops = all.map { cropOf(image, analysis, leafLines, lines, it, options) }
        val cache = engines.cache
        val cachedHits = mutableSetOf<Int>()
        val cachedLines = HashMap<Int, List<VisualOcrLine>>()
        all.forEachIndexed { i, c ->
            val key = cropKey(image, analysis.boxes[c.boxIndex].category, allCrops[i])
            val hit = cache?.rescanOf(key)
            if (hit != null) {
                cachedHits += c.boxIndex
                cachedLines[c.boxIndex] = hit
            }
        }
        val planned =
            RescanPlanner.plan(
                analysis.boxes,
                base.copy(cached = cachedHits),
                options.lowConf,
                options.maxBoxes,
            )
        val toRun = planned.filter { it.boxIndex !in cachedHits }
        val runCrops = toRun.map { c -> allCrops[all.indexOfFirst { it.boxIndex == c.boxIndex }] }
        val (fresh, mosaic) = runDetRec(image, runCrops, engines, options)
        for ((boxIdx, newLines) in fresh) {
            val crop = runCrops.firstOrNull { it.boxIndex == boxIdx } ?: continue
            cache?.putRescan(cropKey(image, analysis.boxes[boxIdx].category, crop), newLines)
        }
        val rescans = cachedLines + fresh
        val merged =
            RescanMerger.merge(
                analysis.boxes,
                lines,
                ownership,
                RescanMerger.MergeInput(planned, rescans, analysis.kids),
                options.dupIou,
            )
        return Outcome(merged.lines, merged.ownership, allCrops.map { it.crop }, mosaic)
    }

    /** 补扫块裁剪（§4.4）：外扩不越邻；字高取归属行最小行高。 */
    private fun cropOf(
        image: VisionImage,
        analysis: VisualTreeBuilder.Analysis,
        leafLines: Map<Int, List<Int>>,
        lines: List<VisualOcrLine>,
        candidate: RescanCandidate,
        options: ParseOptions,
    ): RescanCrop {
        val box = analysis.boxes[candidate.boxIndex].bounds
        val leaves =
            analysis.boxes.indices.filter {
                analysis.kids[it].isNullOrEmpty() && it !in analysis.ownership.drops && it != candidate.boxIndex
            }
        val neighbors = leaves.map { analysis.boxes[it].bounds }
        val textHeight =
            leafLines[candidate.boxIndex]
                .orEmpty()
                .mapNotNull { lines.getOrNull(it)?.bounds?.height }
                .minOrNull()
        return RescanCrop(
            candidate.boxIndex,
            CropMosaic.cropRegion(box, neighbors, options.padRatio, image.width, image.height),
            textHeight,
        )
    }

    /** 补扫 det＋rec（§4.4）：mosaic=true 拼图一次 det，否则逐块；rec 一律从原图裁。 */
    private fun runDetRec(
        image: VisionImage,
        crops: List<RescanCrop>,
        engines: RescanEngines,
        options: ParseOptions,
    ): Pair<Map<Int, List<VisualOcrLine>>, VisionImage?> {
        if (crops.isEmpty()) return emptyMap<Int, List<VisualOcrLine>>() to null
        val scales =
            crops.associate {
                it.boxIndex to CropMosaic.detScale(it.textHeight, options.targetTextPx, options.maxScale)
            }
        val slots =
            if (options.mosaic) {
                CropMosaic.mosaic(crops, scales, options.mosaicGap).slots
            } else {
                crops.map {
                    val scale = scales[it.boxIndex] ?: 1.0
                    MosaicSlot(
                        it.boxIndex,
                        Rect(0, 0, (it.crop.width * scale).toInt(), (it.crop.height * scale).toInt()),
                        it.crop,
                        scale,
                    )
                }
            }
        val out = HashMap<Int, MutableList<VisualOcrLine>>()
        var mosaic: VisionImage? = null
        if (options.mosaic) {
            val canvas = mosaicImage(image, slots)
            mosaic = canvas
            mergeInto(out, collectLines(engines, image, canvas, slots, options.recCascade))
        } else {
            for (slot in slots) {
                val block = blockImage(image, slot)
                mergeInto(out, collectLines(engines, image, block, listOf(slot), options.recCascade))
            }
        }
        return out.mapValues { it.value.toList() } to mosaic
    }

    /** 补扫行归并（按框索引追加）。 */
    private fun mergeInto(
        out: MutableMap<Int, MutableList<VisualOcrLine>>,
        lines: Map<Int, List<VisualOcrLine>>,
    ) {
        for ((boxIdx, newLines) in lines) out.getOrPut(boxIdx) { mutableListOf() } += newLines
    }

    /** det 画布跑一遍，行按中心落块归位，rec 从原图批量识别（整画布走 rec 级联，§4.4）。 */
    private fun collectLines(
        engines: RescanEngines,
        original: VisionImage,
        canvas: VisionImage,
        slots: List<MosaicSlot>,
        cascade: RecCascadeOptions,
    ): Map<Int, List<VisualOcrLine>> {
        val placed =
            engines.detector.detect(canvas).mapNotNull { detBox ->
                val slot = slots.firstOrNull { detBox.bounds.centerIn(it.canvas) }
                val screen = slot?.let { CropMosaic.mapBack(detBox.bounds, it) }
                if (slot != null && screen != null) Triple(slot, detBox, screen) else null
            }
        val boxes = placed.map { OcrBox(it.third, it.second.score) }
        val lines = engines.recognizer.recognizeAll(original, boxes, cascade)
        val out = HashMap<Int, MutableList<VisualOcrLine>>()
        for ((i, line) in lines.withIndex()) {
            if (line != null) out.getOrPut(placed[i].first.boxIndex) { mutableListOf() } += line
        }
        return out.mapValues { it.value.toList() }
    }

    /** 拼图画布（§4.4）：灰底＋各块按 slot 贴入。 */
    private fun mosaicImage(
        image: VisionImage,
        slots: List<MosaicSlot>,
    ): VisionImage {
        val height = slots.fold(0) { acc, s -> maxOf(acc, s.canvas.b) }
        val width = PpOcrCodec.DET_LIMIT_SIDE
        val argb = IntArray(width * height) { GRAY_PAD }
        for (slot in slots) {
            paste(image, slot, argb, width)
        }
        return VisionImage(width, height, argb)
    }

    /** 单块画布（§4.4 逐块模式）：裁剪区按倍率放大。 */
    private fun blockImage(
        image: VisionImage,
        slot: MosaicSlot,
    ): VisionImage {
        val w = maxOf(1, (slot.crop.width * slot.scale).toInt())
        val h = maxOf(1, (slot.crop.height * slot.scale).toInt())
        val argb = IntArray(w * h) { GRAY_PAD }
        paste(image, slot.copy(canvas = Rect(0, 0, w, h)), argb, w)
        return VisionImage(w, h, argb)
    }

    /** 近邻缩放贴块（crop → slot.canvas）。 */
    private fun paste(
        image: VisionImage,
        slot: MosaicSlot,
        argb: IntArray,
        canvasW: Int,
    ) {
        val cw = slot.canvas.width
        val ch = slot.canvas.height
        if (cw <= 0 || ch <= 0) return
        for (y in 0 until ch) {
            val sy = slot.crop.t + y * slot.crop.height / ch
            for (x in 0 until cw) {
                val sx = slot.crop.l + x * slot.crop.width / cw
                argb[(slot.canvas.t + y) * canvasW + (slot.canvas.l + x)] = image.argb[sy * image.width + sx]
            }
        }
    }

    /** 图标缓存键（§4.7）。 */
    private fun iconKey(
        image: VisionImage,
        box: VisualDetection,
        cand: IconCandidate,
    ): RescanCacheKey {
        val region = cand.iconRegion ?: cand.bounds
        return RescanCache.keyOf(box.category, cand.bounds, RescanCache.grayOf(image, region))
    }

    /** 补扫缓存键（§4.7）。 */
    private fun cropKey(
        image: VisionImage,
        category: String,
        crop: RescanCrop,
    ): RescanCacheKey = RescanCache.keyOf(category, crop.crop, RescanCache.grayOf(image, crop.crop))

    /** 单类兜底档经 SubtypeRefiner 细化；v2 多类直出不再细化（与 PoC 同口径）。 */
    private fun categorize(
        detector: VisualDetector,
        kept: List<VisualDetection>,
        lines: List<VisualOcrLine>,
        image: VisionImage,
    ): List<VisualDetection> =
        if (detector.singleClass) {
            SubtypeRefiner.refine(kept, lines, image.width, image.height) { image.solidRatio(it) }
        } else {
            kept
        }

    private fun inArea(
        bounds: Rect,
        area: Rect?,
    ): Boolean = area == null || bounds.intersects(area)

    /** 补扫产物：最终行/归属＋补扫裁剪区＋拼图画布。 */
    private data class Outcome(
        val lines: List<VisualOcrLine>,
        val ownership: Ownership,
        val regions: List<Rect>,
        val mosaic: VisionImage?,
    )
}

/** 拼图/单块画布底色（114 灰，与 PpOcrCodec 补零口径一致）。 */
private const val GRAY_PAD: Int = 0xFF727272.toInt()
