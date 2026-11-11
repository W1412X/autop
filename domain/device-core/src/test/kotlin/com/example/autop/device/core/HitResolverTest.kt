/**
 * 命中回执（docs/design/observation-modes-coordinate-actions §5.4）：
 * 最小可操作节点优先、无操作节点回退最小节点、无命中输出 none。
 */
package com.example.autop.device.core

import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class HitResolverTest {
    private val outer =
        ScreenNode(
            tag = "list",
            act = listOf("scroll"),
            bounds = Rect(0, 0, 500, 500),
            kids =
                listOf(
                    ScreenNode(
                        tag = "btn",
                        text = "确认付款",
                        act = listOf("click"),
                        bounds = Rect(100, 100, 200, 150),
                    ),
                ),
        )

    @Test
    fun `smallest actionable node wins`() {
        val node = HitResolver.resolve(Point(150, 120), model(outer))
        assertThat(node?.tag).isEqualTo("btn")
        assertThat(node?.text).isEqualTo("确认付款")
    }

    @Test
    fun `falls back to smallest node when none actionable`() {
        val plain =
            ScreenNode(
                tag = "group",
                bounds = Rect(0, 0, 500, 500),
                kids = listOf(ScreenNode(tag = "text", text = "标题", bounds = Rect(10, 10, 100, 60))),
            )
        assertThat(HitResolver.resolve(Point(20, 20), model(plain))?.tag).isEqualTo("text")
    }

    @Test
    fun `receipt describes hit with mode`() {
        val receipt =
            Json
                .parseToJsonElement(HitResolver.receipt(Point(150, 120), model(outer), ObserveMode.A11Y))
                .jsonObject
                .getValue("hit")
                .jsonObject
        assertThat(receipt.getValue("tag").jsonPrimitive.content).isEqualTo("btn")
        assertThat(receipt.getValue("from").jsonPrimitive.content).isEqualTo("a11y")
        assertThat(
            receipt.getValue("at").jsonArray.map { it.jsonPrimitive.content },
        ).containsExactly("150", "120").inOrder()
    }

    @Test
    fun `no tree or no containing node renders none`() {
        val withoutTree =
            Json
                .parseToJsonElement(HitResolver.receipt(Point(1, 1), null, null))
                .jsonObject
                .getValue("hit")
                .jsonObject
        assertThat(withoutTree.getValue("none").jsonPrimitive.content).isEqualTo("true")
        val outside =
            Json
                .parseToJsonElement(HitResolver.receipt(Point(900, 900), model(outer), ObserveMode.A11Y))
                .jsonObject
                .getValue("hit")
                .jsonObject
        assertThat(outside.getValue("none").jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `describe omits empty fields and returns null without hit`() {
        val description = HitResolver.describe(Point(150, 120), model(outer)).orEmpty()
        assertThat(description.getValue("tag").jsonPrimitive.content).isEqualTo("btn")
        assertThat(description).doesNotContainKey("icon")
        assertThat(HitResolver.describe(Point(900, 900), model(outer))).isNull()
    }

    @Test
    fun `node receipt carries id tag bounds and at`() {
        val node = outer.kids.single().copy(id = 12, at = Point(150, 125))
        val receipt =
            Json
                .parseToJsonElement(HitResolver.nodeReceipt(node))
                .jsonObject
                .getValue("node")
                .jsonObject
        assertThat(receipt.getValue("id").jsonPrimitive.content).isEqualTo("12")
        assertThat(receipt.getValue("tag").jsonPrimitive.content).isEqualTo("btn")
        assertThat(receipt.getValue("text").jsonPrimitive.content).isEqualTo("确认付款")
        assertThat(
            receipt.getValue("bounds").jsonArray.map { it.jsonPrimitive.content },
        ).containsExactly("100", "100", "200", "150").inOrder()
        assertThat(
            receipt.getValue("at").jsonArray.map { it.jsonPrimitive.content },
        ).containsExactly("150", "125").inOrder()
    }

    @Test
    fun `describeNode matches describe shape`() {
        val node = outer.kids.single()
        val description = HitResolver.describeNode(node)
        assertThat(description.getValue("tag").jsonPrimitive.content).isEqualTo("btn")
        assertThat(description.getValue("text").jsonPrimitive.content).isEqualTo("确认付款")
        assertThat(description).doesNotContainKey("icon")
        assertThat(description).doesNotContainKey("id")
    }

    private fun model(node: ScreenNode): ScreenModel =
        ScreenModel(
            mode = ObserveMode.A11Y,
            windows = listOf(ScreenWindow(app = "应用", kids = listOf(node))),
            quality = ScreenQuality(nodes = 2, interactive = 1),
        )
}
