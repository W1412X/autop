package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.example.autop.device.VisualTree
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 视觉解析编排（设计稿 §3/§6；vp8c ADR-0008）：就绪门槛、部署阈值、单类细化/多类直出、状态分类。 */
internal class VisualParsePipelineTest {
    private class FakeDetector(
        override val ready: Boolean,
        private val dets: List<VisualDetection>,
        override val singleClass: Boolean = false,
        override val deployConf: Double? = null,
    ) : VisualDetector {
        override fun detect(image: VisionImage): List<VisualDetection> = dets
    }

    private class FakeOcr(
        override val ready: Boolean,
        private val lines: List<VisualOcrLine>,
    ) : VisualOcrEngine {
        override fun recognize(image: VisionImage): List<VisualOcrLine> = lines
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
        ocr: FakeOcr,
        states: FakeStates = FakeStates(null),
        minConfidence: Double = 0.35,
        area: Rect? = null,
    ): VisualTree? =
        VisualParsePipeline
            .parse(
                image(),
                detector,
                ocr,
                states,
                ParseOptions(minConfidence = minConfidence, area = area, rescanEnabled = false),
            )?.tree

    @Test
    fun `returns null when engines not ready`() {
        val tree = parse(FakeDetector(false, emptyList()), FakeOcr(true, emptyList()))
        assertThat(tree).isNull()
    }

    @Test
    fun `attaches ocr text to detection`() {
        val line = VisualOcrLine("确定", 0.95, Rect(30, 40, 70, 60))
        val tree = parse(FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))), FakeOcr(true, listOf(line)))
        val texted = tree!!.nodes.first { it.text != null }
        assertThat(texted.text).isEqualTo("确定")
    }

    @Test
    fun `low confidence detections dropped and orphan ocr promoted`() {
        val line = VisualOcrLine("设置", 0.95, Rect(10, 10, 60, 30))
        val tree = parse(FakeDetector(true, listOf(det(0.1, 5, 5, 80, 40))), FakeOcr(true, listOf(line)))
        val nodes = tree!!.nodes
        assertThat(nodes.none { it.className == CategoryMapper.classNameOf(VisualCategory.BUTTON) }).isTrue()
        assertThat(nodes.any { it.text == "设置" }).isTrue()
    }

    @Test
    fun `detections and ocr lines outside content area are dropped`() {
        // N204：装饰区（顶部 30px）的框/文本不进视觉合成树。
        val inArea = det(0.9, 10, 5, 80, 25)
        val outside = det(0.9, 10, 40, 80, 70)
        val outsideLine = VisualOcrLine("12:34", 0.95, Rect(10, 35, 60, 55))
        val tree =
            parse(
                FakeDetector(true, listOf(inArea, outside)),
                FakeOcr(true, listOf(outsideLine)),
                area = Rect(0, 0, 100, 30),
            )
        val nodes = tree!!.nodes
        assertThat(nodes.any { it.bounds == Rect(10, 5, 80, 25) }).isTrue()
        assertThat(nodes.any { it.bounds == Rect(10, 40, 80, 70) }).isFalse()
        assertThat(nodes.any { it.text == "12:34" }).isFalse()
    }

    @Test
    fun `multi class dets skip subtype refining`() {
        // ADR-0008 决策 3：v2 多类直出不再细化（单类档同框会细化成 icon-clickable）。
        val tree = parse(FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))), FakeOcr(true, emptyList()))
        assertThat(tree!!.nodes.any { it.className == "visual.Button" }).isTrue()
    }

    @Test
    fun `single class tier runs subtype refining`() {
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70)), singleClass = true),
                FakeOcr(true, emptyList()),
            )
        assertThat(tree!!.nodes.any { it.className == "visual.ImageButton" }).isTrue()
    }

    @Test
    fun `deploy conf from metadata overrides config floor`() {
        // ADR-0008 决策 3：部署点 conf 读 metadata（0.26 冻结），配置 minConfidence 不上调。
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.3, 10, 30, 90, 70)), deployConf = 0.26),
                FakeOcr(true, emptyList()),
                minConfidence = 0.5,
            )
        assertThat(tree!!.nodes.any { it.bounds == Rect(10, 30, 90, 70) }).isTrue()
    }

    @Test
    fun `state classification writes checked flag and state desc`() {
        val tree =
            parse(
                FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))),
                FakeOcr(true, emptyList()),
                FakeStates(true),
            )
        val node = tree!!.nodes.first { it.bounds == Rect(10, 30, 90, 70) }
        assertThat(node.flags and com.example.autop.device.NodeFlags.CHECKED).isNotEqualTo(0)
        assertThat(node.stateDesc).isEqualTo(CategoryMapper.STATE_ON)
    }

    @Test
    fun `parse exposes final detections and ocr lines as stages`() {
        // N208：调试图渲染取细化+状态后的检测框与 OCR 行（与建树同口径）。
        val line = VisualOcrLine("确定", 0.95, Rect(30, 40, 70, 60))
        val result =
            VisualParsePipeline.parse(
                image(),
                FakeDetector(true, listOf(det(0.9, 10, 30, 90, 70))),
                FakeOcr(true, listOf(line)),
                FakeStates(true),
                ParseOptions(minConfidence = 0.35, rescanEnabled = false),
            )!!
        assertThat(result.detections).hasSize(1)
        assertThat(result.detections.single().checked).isTrue()
        assertThat(result.lines).hasSize(1)
        assertThat(result.lines.single().text).isEqualTo("确定")
    }
}
