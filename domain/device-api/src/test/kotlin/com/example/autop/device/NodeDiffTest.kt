/**
 * 节点差异集合的验收用例（docs/design/flows-contracts-evidence §3.2）：
 * 按稳定键配对，空键节点无法配对。
 */
package com.example.autop.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NodeDiffTest {
    private fun node(
        key: String,
        text: String,
    ) = ScreenNode(tag = "text", text = text, bounds = Rect(0, 0, 10, 10), key = key)

    @Test
    fun `pairs by stable key and reports added removed changed`() {
        val prev = listOf(node("k1", "旧"), node("k2", "同"))
        val cur = listOf(node("k2", "改"), node("k3", "新"))

        val sets = nodeDiffSets(prev, cur)

        assertThat(sets.added.map { it.text }).containsExactly("新")
        assertThat(sets.removed.map { it.text }).containsExactly("旧")
        assertThat(sets.changed.map { it.text }).containsExactly("改")
    }

    @Test
    fun `empty keys never pair`() {
        val sets = nodeDiffSets(listOf(node("", "a")), listOf(node("", "a")))
        assertThat(sets.added).hasSize(1)
        assertThat(sets.removed).hasSize(1)
        assertThat(sets.changed).isEmpty()
    }
}
