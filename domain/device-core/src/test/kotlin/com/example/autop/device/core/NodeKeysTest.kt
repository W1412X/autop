/**
 * 稳定键（新方案 §2.9）：易变文字先抠除再截断、有/无 viewId 的 label 段长度差异。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NodeKeysTest {
    // 真实资产 serializer_rules.json 的 volatileLabelPatterns 口径。
    private val patterns = compileVolatilePatterns(listOf("\\d{1,2}:\\d{2}", "\\d+\\+", "\\d+"))

    private val longLabel = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八"

    private fun indexOf(vararg nodes: NodeSnapshot): SnapshotIndex =
        SnapshotIndex(TestScreens.snapshot(windows = listOf(TestScreens.window()), nodes = nodes.toList()))

    private fun keyOf(
        node: NodeSnapshot,
        label: String,
        index: SnapshotIndex,
    ): String = stableNodeKey("pkg", node, "按钮", label, index, patterns)

    @Test
    fun `数字与角标等易变片段不影响键`() {
        val nodes = listOf(TestScreens.node(1) { children = listOf(2) }, TestScreens.node(2) { parent = 1 })
        val index = indexOf(*nodes.toTypedArray())
        assertThat(keyOf(nodes[1], "消息3条", index)).isEqualTo(keyOf(nodes[1], "消息99+条", index))
    }

    @Test
    fun `时间片段不影响键`() {
        val nodes = listOf(TestScreens.node(1) { children = listOf(2) }, TestScreens.node(2) { parent = 1 })
        val index = indexOf(*nodes.toTypedArray())
        assertThat(keyOf(nodes[1], "12:30 更新", index)).isEqualTo(keyOf(nodes[1], "8:05 更新", index))
    }

    @Test
    fun `去易变后仍按稳定前缀区分`() {
        val nodes = listOf(TestScreens.node(1) { children = listOf(2) }, TestScreens.node(2) { parent = 1 })
        val index = indexOf(*nodes.toTypedArray())
        assertThat(keyOf(nodes[1], "消息3条", index)).isNotEqualTo(keyOf(nodes[1], "设置", index))
    }

    @Test
    fun `无 viewId 时先去易变再截 20 字`() {
        val nodes = listOf(TestScreens.node(1) { children = listOf(2) }, TestScreens.node(2) { parent = 1 })
        val index = indexOf(*nodes.toTypedArray())
        val key = keyOf(nodes[1], "1234567890$longLabel", index)
        // 数字先抠除再截断，故 label 段是纯文字的前 20 字（若先截断会带进数字）。
        assertThat(key.split(KEY_SEPARATOR)[3]).isEqualTo(longLabel.take(20))
    }

    @Test
    fun `同名同角色但父级不同的节点键不同`() {
        // 旧键的“同类兄弟序号”对这种形态会碰撞（如列表各行里的同款开关）。
        val nodes =
            listOf(
                TestScreens.node(1) { children = listOf(2, 3) },
                TestScreens.node(2) {
                    parent = 1
                    children = listOf(4)
                },
                TestScreens.node(3) {
                    parent = 1
                    children = listOf(5)
                },
                TestScreens.node(4) { parent = 2 },
                TestScreens.node(5) { parent = 3 },
            )
        val index = indexOf(*nodes.toTypedArray())
        assertThat(keyOf(nodes[3], "开关", index)).isNotEqualTo(keyOf(nodes[4], "开关", index))
    }

    @Test
    fun `有 viewId 时 label 段只截 8 字`() {
        val nodes =
            listOf(
                TestScreens.node(1) { children = listOf(2) },
                TestScreens.node(2) {
                    parent = 1
                    viewId = "com.example.app:id/btn_ok"
                },
            )
        val index = indexOf(*nodes.toTypedArray())
        val key = keyOf(nodes[1], longLabel, index)
        assertThat(key.split(KEY_SEPARATOR)[3]).isEqualTo(longLabel.take(8))
    }
}
