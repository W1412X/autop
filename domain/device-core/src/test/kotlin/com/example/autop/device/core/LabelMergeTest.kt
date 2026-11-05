/**
 * 标签合并规则（新方案 §2.1）：actionable 才收集、interactive 子树跳过、
 * 去重（“安装 · 安装”）、scrollable 不吞文字。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LabelMergeTest {
    private fun node(
        idx: Int,
        parent: Int?,
        children: List<Int> = emptyList(),
        block: NodeSpec.() -> Unit = {},
    ): NodeSnapshot =
        TestScreens.node(idx) {
            this.parent = parent
            this.children = children
            block()
        }

    private fun serialize(vararg nodes: NodeSnapshot): String {
        val screen = TestScreens.snapshot(windows = listOf(TestScreens.window { rootIdx = 1 }), nodes = nodes.toList())
        return TestScreens.serialize(screen).observation.text
    }

    @Test
    fun scrollableListKeepsChildRowsSeparate() {
        val out =
            serialize(
                node(1, null, listOf(2)),
                node(2, 1, listOf(3, 4)) {
                    className = "android.widget.ListView"
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.SCROLLABLE
                },
                node(3, 2) { text = "在吗" },
                node(4, 2) { text = "在的" },
            )
        // §2.1：只有 SCROLLABLE 的不收集，标签只用它自己的 text/desc。
        assertThat(out).contains("\"tag\":\"list\"")
        assertThat(out).contains("\"act\":[\"scroll\"]")
        assertThat(out).contains("\"text\":\"在吗\"")
        assertThat(out).contains("\"text\":\"在的\"")
        assertThat(out).doesNotContain("在吗 · 在的")
    }

    @Test
    fun actionableCardMergesDescendantLabels() {
        val out =
            serialize(
                node(1, null, listOf(2)),
                node(2, 1, listOf(3, 4)) {
                    className = "android.widget.Button"
                    text = "卡片"
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                },
                node(3, 2) { text = "标题" },
                node(4, 2) { text = "正文" },
            )
        assertThat(out).contains("\"tag\":\"btn\",\"text\":\"卡片 · 标题 · 正文\"")
        assertThat(out).doesNotContain("\"text\":\"标题\"")
        assertThat(out).doesNotContain("\"text\":\"正文\"")
    }

    @Test
    fun duplicateLabelsDedupedButConsumed() {
        val out =
            serialize(
                node(1, null, listOf(2)),
                node(2, 1, listOf(3)) {
                    className = "android.widget.Button"
                    text = "安装"
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                },
                node(3, 2) { text = "安装" },
            )
        // §2.1：与自身重复的跳过但仍 consumed → 只出现一次。
        assertThat(out).contains("\"tag\":\"btn\",\"text\":\"安装\"")
        assertThat(out.split("\"安装\"").size - 1).isEqualTo(1)
        assertThat(out).doesNotContain("安装 · 安装")
    }

    @Test
    fun interactiveSubtreeNotSwallowedByOuterCard() {
        val out =
            serialize(
                node(1, null, listOf(2)),
                node(2, 1, listOf(3)) {
                    className = "android.widget.Button"
                    text = "卡片"
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                },
                node(3, 2, listOf(4)) {
                    className = "android.widget.Button"
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                },
                node(4, 3) { text = "确定" },
            )
        // §2.1：遇 interactive 后代整棵子树跳过，归属权交给更近的控件。
        assertThat(out).contains("\"tag\":\"btn\",\"text\":\"卡片\"")
        assertThat(out).contains("\"tag\":\"btn\",\"text\":\"确定\"")
        assertThat(out).doesNotContain("卡片 · 确定")
    }
}
