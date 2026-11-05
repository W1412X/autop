package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** 文字唯一归属（vp9 §3.2）与不变量（§3.4）。 */
internal class TextOwnershipTest {
    private fun det(
        category: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualDetection(category, 0.9, Rect(l, t, r, b))

    private fun line(
        text: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualOcrLine(text, 0.9, Rect(l, t, r, b))

    @Test
    fun `center falls into smallest box and leaf writes text`() {
        val outer = det(VisualCategory.CONTAINER, 0, 0, 500, 300)
        val inner = det(VisualCategory.BUTTON, 100, 100, 200, 180)
        val l = line("确定", 110, 120, 190, 160)
        val own = TextOwnership.assign(listOf(outer, inner), listOf(l), 0.3)
        assertThat(own.leafLines[1]).containsExactly(0)
        assertThat(own.synthetic).isEmpty()
    }

    @Test
    fun `leaf lines keep reading order for later join`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 300, 200)
        val l0 = line("确定", 10, 10, 100, 50)
        val l1 = line("确定", 10, 100, 100, 140)
        val own = TextOwnership.assign(listOf(button), listOf(l0, l1), 0.3)
        assertThat(own.leafLines[0]).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `container winner creates synthetic text child`() {
        val item = det(VisualCategory.ITEM, 0, 0, 500, 300)
        val icon = det(VisualCategory.ICON, 10, 10, 60, 60)
        val l = line("标题", 100, 120, 300, 160)
        val own = TextOwnership.assign(listOf(item, icon), listOf(l), 0.3)
        assertThat(own.leafLines).isEmpty()
        assertThat(own.synthetic).hasSize(1)
        assertThat(own.synthetic.single().parent).isEqualTo(0)
        assertThat(own.synthetic.single().text).isEqualTo("标题")
    }

    @Test
    fun `orphan line becomes root synthetic text`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 100, 50)
        val l = line("悬浮", 500, 500, 700, 540)
        val own = TextOwnership.assign(listOf(button), listOf(l), 0.3)
        assertThat(own.synthetic).hasSize(1)
        assertThat(own.synthetic.single().parent).isNull()
    }

    @Test
    fun `span line across sibling leaves is recorded and falls back to B`() {
        val root = det(VisualCategory.CONTAINER, 0, 0, 500, 300)
        val a = det(VisualCategory.ITEM, 0, 0, 250, 100)
        val b = det(VisualCategory.ITEM, 250, 0, 500, 100)
        val l = line("横跨", 50, 20, 350, 60)
        val own = TextOwnership.assign(listOf(root, a, b), listOf(l), 0.3)
        assertThat(own.spans).hasSize(1)
        assertThat(own.spans.single().leaves).containsExactly(1, 2)
        assertThat(own.leafLines[1]).containsExactly(0)
    }

    @Test
    fun `single leaf overlap is not a span`() {
        val a = det(VisualCategory.ITEM, 0, 0, 250, 100)
        val b = det(VisualCategory.ITEM, 400, 0, 500, 100)
        val l = line("单侧", 50, 20, 350, 60)
        val own = TextOwnership.assign(listOf(a, b), listOf(l), 0.3)
        assertThat(own.spans).isEmpty()
    }

    @Test
    fun `synthetic lines in same band merge into one node`() {
        val item = det(VisualCategory.ITEM, 0, 0, 500, 400)
        val icon = det(VisualCategory.ICON, 450, 350, 490, 390)
        val l0 = line("名称", 50, 100, 200, 140)
        val l1 = line("说明", 220, 100, 350, 140)
        val own = TextOwnership.assign(listOf(item, icon), listOf(l0, l1), 0.3)
        assertThat(own.synthetic).hasSize(1)
        assertThat(own.synthetic.single().text).isEqualTo("名称 说明")
    }

    @Test
    fun `covered leaf text box is dropped and line falls to winner`() {
        val textBox = det(VisualCategory.TEXT, 0, 0, 200, 100)
        val item = det(VisualCategory.ITEM, 0, 0, 300, 150)
        val l = line("标题", 10, 10, 150, 50)
        val own = TextOwnership.assign(listOf(textBox, item), listOf(l), 0.3)
        assertThat(own.drops).containsExactly(0)
        assertThat(own.leafLines[1]).containsExactly(0)
    }

    @Test
    fun `text box with child is not covered dropped`() {
        val textBox = det(VisualCategory.TEXT, 0, 0, 300, 200)
        val icon = det(VisualCategory.ICON, 100, 100, 160, 160)
        val l = line("标题", 10, 10, 150, 50)
        val own = TextOwnership.assign(listOf(textBox, icon), listOf(l), 0.3)
        assertThat(own.drops).isEmpty()
    }

    @Test
    fun `check passes on clean ownership`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 300, 100)
        val l = line("确定", 10, 10, 100, 50)
        val own = TextOwnership.assign(listOf(button), listOf(l), 0.3)
        var warned = false
        TextOwnership.check(listOf(button), own, listOf(l), 0.5, true) { warned = true }
        assertThat(warned).isFalse()
    }

    @Test
    fun `check throws in debug and warns in release on violation`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 300, 100)
        val icon = det(VisualCategory.ICON, 400, 400, 460, 460)
        val l = line("确定", 10, 10, 100, 50)
        val bad = Ownership(mapOf(0 to listOf(0), 1 to listOf(0)), emptyList(), emptyList(), emptyList())
        var warned: String? = null
        TextOwnership.check(listOf(button, icon), bad, listOf(l), 0.5, false) { warned = it }
        assertThat(warned).contains("ownership_violation")
        val thrown =
            runCatching {
                TextOwnership.check(listOf(button, icon), bad, listOf(l), 0.5, true) { }
            }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `random nested boxes satisfy invariants`() {
        val random = Random(42)
        repeat(30) {
            val boxes =
                (0 until 10).map { i ->
                    val l = random.nextInt(0, 400)
                    val t = random.nextInt(0, 400)
                    det(VisualCategory.CONTAINER, l, t, l + random.nextInt(20, 200), t + random.nextInt(20, 200) + i)
                }
            val lines =
                (0 until 8).map { i ->
                    val l = i * 50 + random.nextInt(0, 10)
                    val t = random.nextInt(0, 400)
                    line("t$i", l, t, l + 40, t + 30)
                }
            val own = TextOwnership.assign(boxes, lines, 0.3)
            TextOwnership.check(boxes, own, lines, 0.5, true) { error("violated: $it") }
        }
    }
}
