package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 伪树合成（设计稿 §6.2）：包含建父子/阅读序/OCR 归属/先序编号/drawingOrder。 */
internal class VisualTreeBuilderTest {
    private fun det(
        category: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualDetection(category, 0.9, Rect(l, t, r, b))

    private fun ocr(
        text: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualOcrLine(text, 0.95, Rect(l, t, r, b))

    @Test
    fun `containment makes parent child and preorder numbering`() {
        val card = det(VisualCategory.LIST_ITEM, 0, 0, 1000, 300)
        val icon = det(VisualCategory.ICON_CLICKABLE, 40, 40, 140, 140)
        val tree = VisualTreeBuilder.build(listOf(card, icon), emptyList(), 1080, 2400)
        assertThat(tree.rootIdx).isEqualTo(0)
        assertThat(tree.nodes).hasSize(3)
        val root = tree.nodes.first { it.idx == 0 }
        val cardNode = tree.nodes.first { it.idx == 1 }
        val iconNode = tree.nodes.first { it.idx == 2 }
        assertThat(root.children).containsExactly(1)
        assertThat(cardNode.parent).isEqualTo(0)
        assertThat(cardNode.children).containsExactly(2)
        assertThat(iconNode.parent).isEqualTo(1)
        assertThat(iconNode.childIndex).isEqualTo(0)
    }

    @Test
    fun `siblings keep reading order as child index`() {
        val left = det(VisualCategory.BUTTON, 0, 0, 200, 100)
        val right = det(VisualCategory.BUTTON, 400, 0, 600, 100)
        val tree = VisualTreeBuilder.build(listOf(right, left), emptyList(), 1080, 2400)
        val leftNode = tree.nodes.first { it.bounds == left.bounds }
        val rightNode = tree.nodes.first { it.bounds == right.bounds }
        assertThat(leftNode.childIndex).isEqualTo(0)
        assertThat(rightNode.childIndex).isEqualTo(1)
    }

    @Test
    fun `ocr line attaches to smallest containing box and covered text det is dropped`() {
        val card = det(VisualCategory.LIST_ITEM, 0, 0, 1000, 300)
        val textDet = det(VisualCategory.TEXT_LABEL, 40, 40, 400, 140)
        val line = ocr("确定", 60, 60, 380, 120)
        val tree = VisualTreeBuilder.build(listOf(card, textDet), listOf(line), 1080, 2400)
        val texts = tree.nodes.mapNotNull { it.text }
        assertThat(texts).containsExactly("确定")
        val cardNode = tree.nodes.first { it.bounds == card.bounds }
        assertThat(cardNode.text).isEqualTo("确定")
    }

    @Test
    fun `orphan ocr line becomes text label node`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 500, 200)
        val line = ocr("侧边文字", 800, 500, 1000, 580)
        val tree = VisualTreeBuilder.build(listOf(button), listOf(line), 1080, 2400)
        val orphan = tree.nodes.first { it.text == "侧边文字" }
        assertThat(orphan.className).isEqualTo("visual.TextView")
        assertThat(orphan.flags and com.example.autop.device.NodeFlags.CLICKABLE).isEqualTo(0)
    }

    @Test
    fun `smaller box gets higher drawing order`() {
        val big = det(VisualCategory.CONTAINER, 0, 0, 1000, 300)
        val small = det(VisualCategory.ICON_CLICKABLE, 40, 40, 140, 140)
        val tree = VisualTreeBuilder.build(listOf(big, small), emptyList(), 1080, 2400)
        val bigNode = tree.nodes.first { it.bounds == big.bounds }
        val smallNode = tree.nodes.first { it.bounds == small.bounds }
        assertThat(smallNode.drawingOrder).isGreaterThan(bigNode.drawingOrder)
    }

    @Test
    fun `nodes come out in preorder`() {
        val card = det(VisualCategory.LIST_ITEM, 0, 0, 1000, 300)
        val icon = det(VisualCategory.ICON_CLICKABLE, 40, 40, 140, 140)
        val line = ocr("标题", 200, 40, 500, 140)
        val tree = VisualTreeBuilder.build(listOf(card, icon), listOf(line), 1080, 2400)
        val idxs = tree.nodes.map { it.idx }
        assertThat(idxs).isInOrder()
    }

    @Test
    fun `detection confidence flows to visual conf`() {
        val box = VisualDetection(VisualCategory.BUTTON, 0.87, Rect(0, 0, 200, 100))
        val tree = VisualTreeBuilder.build(listOf(box), emptyList(), 1080, 2400)
        val node = tree.nodes.first { it.bounds == box.bounds }
        assertThat(node.visualConf!!).isWithin(1e-6f).of(0.87f)
    }

    @Test
    fun `checked state writes check flag and state desc`() {
        val on = VisualDetection(VisualCategory.SWITCH, 0.9, Rect(0, 0, 200, 100), checked = true)
        val off = VisualDetection(VisualCategory.CHECKBOX, 0.9, Rect(300, 0, 500, 100), checked = false)
        val tree = VisualTreeBuilder.build(listOf(on, off), emptyList(), 1080, 2400)
        val onNode = tree.nodes.first { it.bounds == on.bounds }
        val offNode = tree.nodes.first { it.bounds == off.bounds }
        assertThat(onNode.flags and com.example.autop.device.NodeFlags.CHECKED).isNotEqualTo(0)
        assertThat(onNode.stateDesc).isEqualTo(CategoryMapper.STATE_ON)
        assertThat(offNode.flags and com.example.autop.device.NodeFlags.CHECKED).isEqualTo(0)
        assertThat(offNode.stateDesc).isEqualTo(CategoryMapper.STATE_OFF)
    }

    @Test
    fun `icon inside item loses clickable`() {
        // vp8 §8.2：icon 位于 item 子树内时去掉 CLICKABLE（item 本身是交互单元）。
        val item = det(VisualCategory.ITEM, 0, 0, 1000, 300)
        val inner = det(VisualCategory.ICON, 40, 40, 140, 140)
        val tree = VisualTreeBuilder.build(listOf(item, inner), emptyList(), 1080, 2400)
        val iconNode = tree.nodes.first { it.bounds == inner.bounds }
        assertThat(iconNode.flags and com.example.autop.device.NodeFlags.CLICKABLE).isEqualTo(0)
    }

    @Test
    fun `icon outside item keeps clickable`() {
        val icon = det(VisualCategory.ICON, 40, 40, 140, 140)
        val tree = VisualTreeBuilder.build(listOf(icon), emptyList(), 1080, 2400)
        val iconNode = tree.nodes.first { it.bounds == icon.bounds }
        assertThat(iconNode.flags and com.example.autop.device.NodeFlags.CLICKABLE).isNotEqualTo(0)
    }

    @Test
    fun `bottom tab row becomes tab group with tab children`() {
        // ADR-0008 决策 7：底部 12% 内一行 ≥3 个尺寸相近的 item → 公共父节点标签栏。
        val tabs =
            listOf(
                det(VisualCategory.ITEM, 0, 940, 100, 1000),
                det(VisualCategory.ICON, 110, 940, 210, 1000),
                det(VisualCategory.BUTTON, 220, 940, 320, 1000),
            )
        val tree = VisualTreeBuilder.build(tabs, emptyList(), 1080, 1000)
        val group = tree.nodes.first { it.className == "visual.TabWidget" }
        val members = tree.nodes.filter { it.className == "visual.Tab" }
        assertThat(members).hasSize(3)
        assertThat(members.map { it.parent }).containsExactly(group.idx, group.idx, group.idx)
    }
}
