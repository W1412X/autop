package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.example.autop.device.VisualTree
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 视觉解析编排（简化版）：就绪门槛、部署阈值、单类细化/多类直出、状态分类、逐叶子采集。 */
internal class VisualParsePipelineTest {
    private class FakeDetector(
        override val ready: Boolean,
        private val dets: List<VisualDetection>,
        override val singleClass: Boolean = false,
        override val deployConf: Double? = null,
    ) : VisualDetector {
        override fun detect(image: VisionImage): List<VisualDetection> = dets
    }

    /** 识别桩：按框回放固定文本（每个叶子都会被调到）。 */
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

    private fun parse(
        detector: FakeDetector,
        recognizer: FakeRecognizer,
        states: FakeStates = FakeStates(null),
        minConfidence: Double = 0.35,
        area: Rect? = null,
    ): VisualTree? =
        VisualParsePipeline
            .parse(
                image(),
                detector,
                recognizer,
                states,
                ParseOptions(minConfidence = minConfidence, area = area),
            )?.tree

    @Test
    fun `returns null when engines not ready`() {
        val tree = parse(FakeDetector(false, emptyList()), FakeRecognizer(true))
        assertThat(tree).isNull()
        val tree2 = parse(FakeDetector(true, emptyList()), FakeRecognizer(false))
        assertThat(tree2).isNull()
    }

    @Test
    fun `every leaf gets ocr regardless of category`() {
        val left = det(0.9, 5, 5, 45, 45)
        val right = det(0.9, 55, 5, 95, 45)
        val recognizer = FakeRecognizer(true) { if (it.bounds.l == 5) "确定" else "取消" }
        val tree = parse(FakeDetector(true, listOf(left, right)), recognizer)
        val texts = tree!!.nodes.mapNotNull { it.text }
        assertThat(texts).containsExactly("确定", "取消")
        assertThat(recognizer.seen).containsExactly(left.bounds, right.bounds)
    }

    @Test
    fun `low confidence detections dropped without orphan promotion`() {
        val tree = parse(FakeDetector(true, listOf(det(0.1, 5, 5, 80, 40))), FakeRecognizer(true) { "设置" })
        assertThat(tree!!.nodes.none { it.className == CategoryMapper.classNameOf(VisualCategory.BUTTON) }).isTrue()
        assertThat(tree.nodes.none { it.text == "设置" }).isTrue()
    }

    @Test
    fun `detections outside content area are dropped and not scanned`() {
        // N204：装饰区（顶部 30px）的框不进视觉合成树，也不进 OCR。
        val inArea = det(0.9, 10, 5, 80, 25)
        val outside = det(0.9, 10, 40, 80, 70)
        val recognizer = FakeRecognizer(true) { "12:34" }
        val tree = parse(FakeDetector(true, listOf(inArea, outside)), recognizer, area = Rect(0, 0, 100, 30))
        val nodes = tree!!.nodes
        assertThat(nodes.any { it.bounds == Rect(10, 5, 80, 25) }).isTrue()
        assertThat(nodes.any { it.bounds == Rect(10, 40, 80, 70) }).isFalse()
        assertThat(recognizer.seen).containsExactly(Rect(10, 5, 80, 25))
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
                FakeStates(true),
            )
        val node = tree!!.nodes.first { it.bounds == Rect(10, 30, 90, 70) }
        assertThat(node.flags and com.example.autop.device.NodeFlags.CHECKED).isNotEqualTo(0)
        assertThat(node.stateDesc).isEqualTo(CategoryMapper.STATE_ON)
    }

    @Test
    fun `parse exposes final detections and leaf ocr lines as stages`() {
        val result =
            VisualParsePipeline.parse(
                image(),
                FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))),
                FakeRecognizer(true) { "确定" },
                FakeStates(true),
                ParseOptions(minConfidence = 0.35),
            )!!
        assertThat(result.detections).hasSize(1)
        assertThat(result.detections.single().checked).isTrue()
        assertThat(result.lines).hasSize(1)
        assertThat(result.lines.single().text).isEqualTo("确定")
    }
}
