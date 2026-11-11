/**
 * 节点 id 分配（coordinate-node-action-split §3.2）：首次按出现顺序领 id、
 * 配对沿用 prev id、不可配对全部领新 id、计数单调不复用、visual 不分配。
 */
package com.example.autop.device.core

import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NodeIdsTest {
    private val patterns = listOf(Regex("\\d+"))

    @Test
    fun `first observation assigns ids in appearance order`() {
        val model = model(listOf(node("btn", "确定", act = true), node("btn", "取消", act = true)))
        var counter = 1
        val out = assign(model, next = { counter++ })
        assertThat(out.flatten().map { it.id }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `nodes without actions get no id`() {
        val model = model(listOf(node("text", "标题"), node("btn", "确定", act = true)))
        var counter = 1
        val out = assign(model, next = { counter++ })
        assertThat(out.flatten()[0].id).isNull()
        assertThat(out.flatten()[1].id).isEqualTo(1)
    }

    @Test
    fun `paired nodes keep previous ids`() {
        val prev =
            model(
                listOf(
                    node("btn", "确定", act = true, id = 1),
                    node("btn", "取消", act = true, id = 2),
                ),
            )
        val cur = model(listOf(node("btn", "确定", act = true), node("btn", "取消", act = true)))
        var counter = 100
        val out = assign(cur, prev, prevComparable = true, next = { counter++ })
        assertThat(out.flatten().map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(counter).isEqualTo(100)
    }

    @Test
    fun `not comparable assigns brand new ids`() {
        val prev = model(listOf(node("btn", "确定", act = true, id = 9)))
        val cur = model(listOf(node("btn", "确定", act = true)))
        var counter = 50
        val out = assign(cur, prev, prevComparable = false, next = { counter++ })
        assertThat(out.flatten().single().id).isEqualTo(50)
    }

    @Test
    fun `counter is monotonic and never reused`() {
        var counter = 1
        val next = { counter++ }
        val out1 = assign(model(listOf(node("btn", "确定", act = true))), next = next)
        val out2 = assign(model(listOf(node("btn", "取消", act = true))), next = next)
        assertThat(out1.flatten().single().id).isEqualTo(1)
        assertThat(out2.flatten().single().id).isEqualTo(2)
    }

    @Test
    fun `visual model is never assigned`() {
        val model =
            ScreenModel(
                mode = ObserveMode.VISUAL,
                windows = listOf(ScreenWindow(app = "app", kids = listOf(node("btn", "确定", act = true)))),
                quality = ScreenQuality(nodes = 1, interactive = 1),
            )
        var counter = 1
        val out = assign(model, next = { counter++ })
        assertThat(out.flatten().single().id).isNull()
        assertThat(counter).isEqualTo(1)
    }

    private fun assign(
        model: ScreenModel,
        prev: ScreenModel? = null,
        prevComparable: Boolean = false,
        next: () -> Int,
    ): ScreenModel = NodeIds.assign(model, prev, prevComparable, 12, patterns, next)

    private fun node(
        tag: String,
        text: String,
        act: Boolean = false,
        id: Int? = null,
        bounds: Rect = Rect(0, 0, 100, 50),
    ) = ScreenNode(tag = tag, text = text, act = if (act) listOf("click") else emptyList(), bounds = bounds, id = id)

    private fun model(nodes: List<ScreenNode>) =
        ScreenModel(
            mode = ObserveMode.A11Y,
            windows = listOf(ScreenWindow(app = "app", kids = nodes)),
            quality =
                ScreenQuality(
                    nodes = nodes.size,
                    interactive = nodes.count { it.act.isNotEmpty() },
                ),
        )
}
