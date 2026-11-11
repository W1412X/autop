/**
 * 节点配对（agent-harness-overhaul WP1.5）：按内容＋位置容差配对，
 * 视觉识别顺序变化不产生“同一文字新增+消失”。
 */
package com.example.autop.device.core

import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenNodeMatcherTest {
    private val volatile = listOf(Regex("\\d+"))

    @Test
    fun `identical nodes produce empty result`() {
        val nodes = listOf(node("btn", "确定"), node("text", "设置", top = 100))
        assertThat(ScreenNodeMatcher.match(nodes, nodes, TOLERANCE, volatile).empty).isTrue()
    }

    @Test
    fun `same label shifted within tolerance is not a change`() {
        val prev = listOf(node("btn", "确定", top = 0))
        val cur = listOf(node("btn", "确定", top = SHIFT))
        assertThat(ScreenNodeMatcher.match(prev, cur, TOLERANCE, volatile).empty).isTrue()
    }

    @Test
    fun `same label shifted beyond tolerance is added and removed`() {
        val prev = listOf(node("btn", "确定", top = 0))
        val cur = listOf(node("btn", "确定", top = 100))
        val result = ScreenNodeMatcher.match(prev, cur, TOLERANCE, volatile)
        assertThat(result.added).hasSize(1)
        assertThat(result.removed).hasSize(1)
        assertThat(result.changed).isEmpty()
    }

    @Test
    fun `reordered visual nodes are not reported`() {
        val a = node("visual.Button", "搜索", top = 0)
        val b = node("visual.Button", "返回", top = 200)
        val result = ScreenNodeMatcher.match(listOf(a, b), listOf(b, a), TOLERANCE, volatile)
        assertThat(result.empty).isTrue()
    }

    @Test
    fun `label change at same position is changed`() {
        val prev = listOf(node("btn", "确定"))
        val cur = listOf(node("btn", "取消"))
        val result = ScreenNodeMatcher.match(prev, cur, TOLERANCE, volatile)
        assertThat(result.changed.single().text).isEqualTo("取消")
        assertThat(result.added).isEmpty()
        assertThat(result.removed).isEmpty()
    }

    @Test
    fun `volatile digits in label are ignored`() {
        val prev = listOf(node("text", "已用 12:00"))
        val cur = listOf(node("text", "已用 12:01"))
        assertThat(ScreenNodeMatcher.match(prev, cur, TOLERANCE, volatile).empty).isTrue()
    }

    @Test
    fun `checked state change is changed`() {
        val prev = listOf(node("checkbox", "蓝牙", st = listOf("unchecked")))
        val cur = listOf(node("checkbox", "蓝牙", st = listOf("checked")))
        val result = ScreenNodeMatcher.match(prev, cur, TOLERANCE, volatile)
        assertThat(result.changed.single().st).containsExactly("checked")
    }

    @Test
    fun `pairs records matched index pairs`() {
        val prev = listOf(node("btn", "确定"), node("text", "标题", top = 100))
        val cur = listOf(node("text", "标题", top = 100), node("btn", "确定"))
        val result = ScreenNodeMatcher.match(prev, cur, TOLERANCE, volatile)
        assertThat(result.pairs).containsExactly(0 to 1, 1 to 0)
        assertThat(result.added).isEmpty()
        assertThat(result.removed).isEmpty()
    }

    private fun node(
        tag: String,
        label: String,
        top: Int = 0,
        st: List<String> = emptyList(),
    ): ScreenNode =
        ScreenNode(
            tag = tag,
            text = label,
            st = st,
            bounds = Rect(0, top, 100, top + 50),
        )

    private companion object {
        const val TOLERANCE = 12
        const val SHIFT = 10
    }
}
