package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.example.autop.device.VisualTree
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 视觉解析编排：就绪门槛、部署阈值、单类细化/多类直出、状态分类、叶子内 det→rec（ADR-0026）。 */
internal class VisualParsePipelineTest {
    private class FakeDetector(
        override val ready: Boolean,
        private val dets: List<VisualDetection>,
        override val singleClass: Boolean = false,
        override val deployConf: Double? = null,
    ) : VisualDetector {
        override fun detect(image: VisionImage): List<VisualDetection> = dets
    }

    /** 文字定位桩：默认把整个叶子裁剪区域当作一行；可按区域自定义行框。 */
    private class FakeTextDetector(
        override val ready: Boolean = true,
        private val linesOf: (Rect) -> List<Rect> = { listOf(it) },
    ) : TextDetector {
        val regions = mutableListOf<Rect>()

        override fun locate(
            image: VisionImage,
            region: Rect,
        ): List<OcrBox> {
            regions += region
            return linesOf(region).map { OcrBox(it, 0.9) }
        }
    }

    /** 识别桩：按行框回放固定文本（det 找到的每一行都会被调到）。 */
    private class FakeRecognizer(
        override val ready: Boolean,
        private val textOf: (OcrBox) -> String? = { null },
    ) : TextRecognizer {
        val seen = mutableListOf<Rect>()

        override fun recognize(
            image: VisionImage,
            box: OcrBox,
        ): VisualOcrLine? {
            seen += box.bounds
            val text = textOf(box) ?: return null
            return VisualOcrLine(text, 0.95, box.bounds)
        }
    }

    /** 状态分类桩：按类别给固定三态。 */
    private class FakeStates(
        private val checked: Boolean?,
    ) : VisualStateClassifier {
        override val ready: Boolean = true

        override fun classify(
            image: VisionImage,
            detections: List<VisualDetection>,
        ): List<VisualDetection> = detections.map { it.copy(checked = checked) }
    }

    private fun image(): VisionImage = VisionImage(100, 100, IntArray(100 * 100) { 0xFFFFFFFF.toInt() })

    private fun det(
        conf: Double,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualDetection(VisualCategory.BUTTON, conf, Rect(l, t, r, b))

    private fun result(
        detector: FakeDetector,
        recognizer: FakeRecognizer,
        textDetector: FakeTextDetector = FakeTextDetector(),
        states: FakeStates = FakeStates(null),
        minConfidence: Double = 0.35,
        area: Rect? = null,
    ): VisualParseResult? =
        runBlocking {
            VisualParsePipeline.parse(
                image(),
                VisionEngines(detector, textDetector, recognizer, states),
                ParseOptions(minConfidence = minConfidence, area = area),
                InferenceGate(1, Dispatchers.Unconfined),
            )
        }

    private fun parse(
        detector: FakeDetector,
        recognizer: FakeRecognizer,
        textDetector: FakeTextDetector = FakeTextDetector(),
        states: FakeStates = FakeStates(null),
        minConfidence: Double = 0.35,
        area: Rect? = null,
    ): VisualTree? = result(detector, recognizer, textDetector, states, minConfidence, area)?.tree

    @Test
    fun `returns null when engines not ready`() {
        val tree = parse(FakeDetector(false, emptyList()), FakeRecognizer(true))
        assertThat(tree).isNull()
        val tree2 = parse(FakeDetector(true, emptyList()), FakeRecognizer(false))
        assertThat(tree2).isNull()
        val tree3 = parse(FakeDetector(true, emptyList()), FakeRecognizer(true), FakeTextDetector(ready = false))
        assertThat(tree3).isNull()
    }

    @Test
    fun `every leaf is located and recognized regardless of category`() {
        val left = det(0.9, 5, 5, 45, 45)
        val right = det(0.9, 55, 5, 95, 45)
        val recognizer = FakeRecognizer(true) { if (it.bounds.centerX < 50) "确定" else "取消" }
        val textDetector = FakeTextDetector()
        val tree = parse(FakeDetector(true, listOf(left, right)), recognizer, textDetector)
        val texts = tree!!.nodes.mapNotNull { it.text }
        assertThat(texts).containsExactly("确定", "取消")
        // det 在叶子裁剪（与 rec 同样的外扩）内跑，rec 收到的是 det 返回的行框。
        val regions = listOf(left, right).map { PpOcrCodec.recCrop(image(), it.bounds) }
        assertThat(textDetector.regions).containsExactlyElementsIn(regions)
        assertThat(recognizer.seen).containsExactlyElementsIn(regions)
    }

    @Test
    fun `leaf without located lines has no text and skips recognition`() {
        val recognizer = FakeRecognizer(true) { "不该出现" }
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.9, 5, 5, 45, 45))),
                recognizer,
                FakeTextDetector { emptyList() },
            )
        assertThat(tree!!.nodes.mapNotNull { it.text }).isEmpty()
        assertThat(recognizer.seen).isEmpty()
    }

    @Test
    fun `multi line leaf joins lines in reading order`() {
        val leaf = det(0.9, 10, 10, 90, 90)
        val top = Rect(12, 15, 88, 30)
        val bottom = Rect(12, 60, 88, 75)
        val recognizer = FakeRecognizer(true) { if (it.bounds.t < 40) "第一行" else "第二行" }
        val tree = parse(FakeDetector(true, listOf(leaf)), recognizer, FakeTextDetector { listOf(top, bottom) })
        assertThat(tree!!.nodes.single { it.bounds == leaf.bounds }.text).isEqualTo("第一行\n第二行")
    }

    @Test
    fun `lines whose center is outside the leaf are dropped`() {
        // 叶子裁剪带外扩，会带入相邻控件的字：中心不在叶子框内的行不属于该叶子。
        val leaf = det(0.9, 20, 20, 60, 60)
        val inside = Rect(22, 30, 58, 50)
        val neighbor = Rect(62, 30, 80, 50)
        val recognizer = FakeRecognizer(true) { if (it.bounds == inside) "本叶子" else "邻居" }
        val tree = parse(FakeDetector(true, listOf(leaf)), recognizer, FakeTextDetector { listOf(inside, neighbor) })
        assertThat(tree!!.nodes.mapNotNull { it.text }).containsExactly("本叶子")
        assertThat(recognizer.seen).containsExactly(inside)
    }

    @Test
    fun `low confidence detections dropped without orphan promotion`() {
        val tree = parse(FakeDetector(true, listOf(det(0.1, 5, 5, 80, 40))), FakeRecognizer(true) { "设置" })
        assertThat(tree!!.nodes.none { it.className == CategoryMapper.classNameOf(VisualCategory.BUTTON) }).isTrue()
        assertThat(tree.nodes.none { it.text == "设置" }).isTrue()
    }

    @Test
    fun `detections outside content area are dropped and not scanned`() {
        // N204：装饰区（顶部 30px）的框不进视觉合成树，也不进 det/OCR。
        val inArea = det(0.9, 10, 5, 80, 25)
        val outside = det(0.9, 10, 40, 80, 70)
        val recognizer = FakeRecognizer(true) { "12:34" }
        val textDetector = FakeTextDetector()
        val tree =
            parse(FakeDetector(true, listOf(inArea, outside)), recognizer, textDetector, area = Rect(0, 0, 100, 30))
        val nodes = tree!!.nodes
        assertThat(nodes.any { it.bounds == Rect(10, 5, 80, 25) }).isTrue()
        assertThat(nodes.any { it.bounds == Rect(10, 40, 80, 70) }).isFalse()
        assertThat(textDetector.regions).containsExactly(PpOcrCodec.recCrop(image(), inArea.bounds))
    }

    @Test
    fun `multi class dets skip subtype refining`() {
        // ADR-0008 决策 3：v2 多类直出不再细化（单类档同框会细化成 icon-clickable）。
        val tree = parse(FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))), FakeRecognizer(true))
        assertThat(tree!!.nodes.any { it.className == "visual.Button" }).isTrue()
    }

    @Test
    fun `single class tier runs subtype refining`() {
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70)), singleClass = true),
                FakeRecognizer(true),
            )
        assertThat(tree!!.nodes.any { it.className == "visual.ImageButton" }).isTrue()
    }

    @Test
    fun `deploy conf from metadata overrides config floor`() {
        // ADR-0008 决策 3：部署点 conf 读 metadata（0.26 冻结），配置 minConfidence 不上调。
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.3, 10, 30, 90, 70)), deployConf = 0.26),
                FakeRecognizer(true),
                minConfidence = 0.5,
            )
        assertThat(tree!!.nodes.any { it.bounds == Rect(10, 30, 90, 70) }).isTrue()
    }

    @Test
    fun `state classification writes checked flag and state desc`() {
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))),
                FakeRecognizer(true),
                states = FakeStates(true),
            )
        val node = tree!!.nodes.first { it.bounds == Rect(10, 30, 90, 70) }
        assertThat(node.flags and com.example.autop.device.NodeFlags.CHECKED).isNotEqualTo(0)
        assertThat(node.stateDesc).isEqualTo(CategoryMapper.STATE_ON)
    }

    @Test
    fun `parse exposes final detections and per line ocr results as stages`() {
        val leaf = det(0.9, 10, 10, 90, 90)
        val lines = listOf(Rect(12, 15, 88, 30), Rect(12, 60, 88, 75))
        val result =
            result(
                FakeDetector(true, listOf(leaf)),
                FakeRecognizer(true) { if (it.bounds.t < 40) "确定" else "取消" },
                FakeTextDetector { lines },
                FakeStates(true),
            )!!
        assertThat(result.detections).hasSize(1)
        assertThat(result.detections.single().checked).isTrue()
        assertThat(result.lines.map { it.text }).containsExactly("确定", "取消").inOrder()
    }
}
