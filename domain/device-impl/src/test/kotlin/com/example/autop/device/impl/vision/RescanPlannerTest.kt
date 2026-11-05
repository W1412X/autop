package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 补扫候选规划（vp9 §4.3）：A/E/B/D/C 分类、排除、勾选类口径、排序与名额。 */
internal class RescanPlannerTest {
    private fun det(
        category: String,
        l: Int = 0,
        t: Int = 0,
        r: Int = 100,
        b: Int = 50,
    ) = VisualDetection(category, 0.9, Rect(l, t, r, b))

    private val emptyContext =
        RescanPlanner.Context(emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptySet())

    private fun plan(
        boxes: List<VisualDetection>,
        context: RescanPlanner.Context = emptyContext,
        maxBoxes: Int = Int.MAX_VALUE,
    ) = RescanPlanner.plan(boxes, context, 0.6, maxBoxes)

    @Test
    fun `interactive leaf without text or icon is class A`() {
        val out = plan(listOf(det(VisualCategory.BUTTON)))
        assertThat(out).hasSize(1)
        assertThat(out.single().kind).isEqualTo(RescanCandidate.KIND_A)
        assertThat(out.single().priority).isEqualTo(1)
    }

    @Test
    fun `image progress slider are excluded`() {
        val boxes = listOf(det(VisualCategory.IMAGE), det(VisualCategory.PROGRESS), det(VisualCategory.SLIDER))
        assertThat(plan(boxes)).isEmpty()
    }

    @Test
    fun `checkable only participates in class A`() {
        val bare = plan(listOf(det(VisualCategory.SWITCH)))
        assertThat(bare).hasSize(1)
        assertThat(bare.single().kind).isEqualTo(RescanCandidate.KIND_A)
        val filled =
            plan(
                listOf(det(VisualCategory.SWITCH)),
                RescanPlanner.Context(
                    leafLines = mapOf(0 to listOf(0)),
                    spanLeaves = listOf(0),
                    lineConf = mapOf(0 to listOf(0.2)),
                ),
            )
        assertThat(filled).isEmpty()
    }

    @Test
    fun `span leaves are class E`() {
        // 有文字不命中 A；跨框行涉及 → E。
        val out =
            plan(
                listOf(det(VisualCategory.ITEM)),
                RescanPlanner.Context(
                    leafLines = mapOf(0 to listOf(0)),
                    lineConf = mapOf(0 to listOf(0.9)),
                    spanLeaves = listOf(0),
                ),
            )
        assertThat(out.single().kind).isEqualTo(RescanCandidate.KIND_E)
    }

    @Test
    fun `uncovered text box is class B`() {
        val out = plan(listOf(det(VisualCategory.TEXT)))
        assertThat(out.single().kind).isEqualTo(RescanCandidate.KIND_B)
        val covered = plan(listOf(det(VisualCategory.TEXT)), RescanPlanner.Context(coveredText = setOf(0)))
        assertThat(covered).isEmpty()
    }

    @Test
    fun `item subtree without text or icon is class D`() {
        // 子框 image（排除类）：item 子树无任何非空 text/icon → D；image 自身不补扫。
        val boxes = listOf(det(VisualCategory.ITEM, 0, 0, 300, 200), det(VisualCategory.IMAGE, 10, 10, 60, 60))
        val out = plan(boxes, RescanPlanner.Context(kids = mapOf(0 to listOf(1))))
        assertThat(out).hasSize(1)
        assertThat(out.single().kind).isEqualTo(RescanCandidate.KIND_D)
        assertThat(out.single().boxIndex).isEqualTo(0)
    }

    @Test
    fun `low confidence line is class C`() {
        val context = RescanPlanner.Context(leafLines = mapOf(0 to listOf(0)), lineConf = mapOf(0 to listOf(0.3)))
        val out = plan(listOf(det(VisualCategory.BUTTON)), context)
        assertThat(out.single().kind).isEqualTo(RescanCandidate.KIND_C)
    }

    @Test
    fun `quota fills by priority then area and cached skips quota`() {
        val boxes =
            listOf(
                det(VisualCategory.BUTTON, 0, 0, 50, 50),
                det(VisualCategory.BUTTON, 100, 0, 200, 100),
                det(VisualCategory.TEXT, 300, 0, 320, 20),
            )
        // A×2（面积小者优先）＋B；maxBoxes=2 时取小 button 与大 button。
        val out = plan(boxes, maxBoxes = 2)
        assertThat(out.map { it.boxIndex }).containsExactly(0, 1).inOrder()
        // box0 缓存命中不计名额 → A 大框与 B 都进。
        val cachedOut = plan(boxes, RescanPlanner.Context(cached = setOf(0)), maxBoxes = 2)
        assertThat(cachedOut.map { it.boxIndex }).containsExactly(0, 1, 2)
    }
}
