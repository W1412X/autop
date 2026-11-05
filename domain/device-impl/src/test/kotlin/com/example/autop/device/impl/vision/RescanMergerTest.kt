package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 补扫合并（vp9 §4.5）：中心过滤、替换/丢弃、归本框、E 类回落。 */
internal class RescanMergerTest {
    private fun det(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        category: String = VisualCategory.BUTTON,
    ) = VisualDetection(category, 0.9, Rect(l, t, r, b))

    private fun line(
        text: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        conf: Double = 0.9,
    ) = VisualOcrLine(text, conf, Rect(l, t, r, b))

    private fun merge(
        boxes: List<VisualDetection>,
        lines: List<VisualOcrLine>,
        ownership: Ownership,
        candidates: List<RescanCandidate>,
        rescans: Map<Int, List<VisualOcrLine>>,
        kids: Map<Int, List<Int>> = emptyMap(),
    ): RescanMerge {
        val input = RescanMerger.MergeInput(candidates, rescans, kids)
        return RescanMerger.merge(boxes, lines, ownership, input, 0.5)
    }

    private val boxA = det(0, 0, 300, 100)
    private val boxB = det(400, 0, 700, 100)
    private val candidate = RescanCandidate(0, RescanCandidate.KIND_A, 1)

    @Test
    fun `line outside host box is dropped`() {
        val lines = listOf(line("旧", 10, 10, 100, 50, 0.5))
        val ownership = Ownership(mapOf(0 to listOf(0)), emptyList(), emptyList(), emptyList())
        val rescans = mapOf(0 to listOf(line("新", 500, 10, 600, 50)))
        val out = merge(listOf(boxA, boxB), lines, ownership, listOf(candidate), rescans)
        assertThat(out.lines).hasSize(1)
        assertThat(out.lines.single().text).isEqualTo("旧")
    }

    @Test
    fun `same host line with higher confidence replaces in place`() {
        val lines = listOf(line("旧", 10, 10, 100, 50, 0.5))
        val ownership = Ownership(mapOf(0 to listOf(0)), emptyList(), emptyList(), emptyList())
        val rescans = mapOf(0 to listOf(line("新", 10, 10, 100, 50, 0.9)))
        val out = merge(listOf(boxA, boxB), lines, ownership, listOf(candidate), rescans)
        assertThat(out.lines).hasSize(1)
        assertThat(out.lines.single().text).isEqualTo("新")
        assertThat(out.ownership.leafLines[0]).containsExactly(0)
    }

    @Test
    fun `overlap with other host line is dropped`() {
        val lines = listOf(line("他框", 500, 10, 600, 50, 0.5))
        val ownership = Ownership(mapOf(1 to listOf(0)), emptyList(), emptyList(), emptyList())
        val rescans = mapOf(0 to listOf(line("新", 505, 10, 610, 50, 0.9)))
        val out = merge(listOf(boxA, boxB), lines, ownership, listOf(candidate), rescans)
        assertThat(out.lines.single().text).isEqualTo("他框")
    }

    @Test
    fun `fresh line attaches to leaf host`() {
        val lines = emptyList<VisualOcrLine>()
        val ownership = Ownership(emptyMap(), emptyList(), emptyList(), emptyList())
        val rescans = mapOf(0 to listOf(line("补到", 10, 10, 100, 50)))
        val out = merge(listOf(boxA), lines, ownership, listOf(candidate), rescans)
        assertThat(out.lines).hasSize(1)
        assertThat(out.ownership.leafLines[0]).containsExactly(0)
    }

    @Test
    fun `fresh line on container host becomes synthetic node`() {
        val lines = emptyList<VisualOcrLine>()
        val ownership = Ownership(emptyMap(), emptyList(), emptyList(), emptyList())
        val dCandidate = RescanCandidate(0, RescanCandidate.KIND_D, 4)
        val rescans = mapOf(0 to listOf(line("合成", 10, 10, 100, 50)))
        val kids = mapOf(0 to listOf(1))
        val out = merge(listOf(boxA, det(10, 10, 60, 60)), lines, ownership, listOf(dCandidate), rescans, kids)
        assertThat(out.ownership.synthetic).hasSize(1)
        val synth = out.ownership.synthetic.single()
        assertThat(synth.parent).isEqualTo(0)
        assertThat(synth.text).isEqualTo("合成")
    }

    @Test
    fun `span line dropped when all leaves filled else kept`() {
        val lines = listOf(line("横跨", 10, 10, 400, 50, 0.9))
        val span = SpanLine(0, listOf(0, 1))
        val candidates =
            listOf(RescanCandidate(0, RescanCandidate.KIND_E, 2), RescanCandidate(1, RescanCandidate.KIND_E, 2))
        // 都补到字：原跨框行丢弃。
        val filled = Ownership(mapOf(0 to listOf(0), 1 to emptyList()), emptyList(), listOf(span), emptyList())
        val rescans =
            mapOf(
                0 to listOf(line("左", 10, 10, 100, 50)),
                1 to listOf(line("右", 410, 10, 500, 50)),
            )
        val out = merge(listOf(boxA, boxB), lines, filled, candidates, rescans)
        assertThat(out.lines.map { it.text }).containsExactly("左", "右")
        assertThat(out.ownership.spans).isEmpty()
        // 只补到一侧：原行保留。
        val partial = Ownership(mapOf(0 to listOf(0), 1 to emptyList()), emptyList(), listOf(span), emptyList())
        val out2 =
            merge(
                listOf(boxA, boxB),
                lines,
                partial,
                candidates,
                mapOf(0 to listOf(line("左", 10, 10, 100, 50))),
            )
        assertThat(out2.lines.map { it.text }).contains("横跨")
    }
}
