/**
 * 观测 JSON 渲染（docs/design/observation-modes-coordinate-actions §3）：
 * 顶层字段、节点排版、空值省略、cached、建议尾行与差量。
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ScreenJsonRendererTest {
    private val coordText = "坐标说明"

    @Test
    fun `a11y full output is valid json with mode fields and quality`() {
        val node =
            ScreenNode(
                tag = "btn",
                text = "确定",
                cls = "Button",
                vid = "ok",
                act = listOf("click"),
                st = listOf("disabled"),
                bounds = Rect(100, 200, 300, 400),
                at = Point(200, 300),
            )
        val model = model(ObserveMode.A11Y, listOf(node))
        val screen = screenOf(ScreenJsonRenderer.render(model, coordText, null))

        assertThat(screen.str("v")).isEqualTo("1")
        assertThat(screen.str("mode")).isEqualTo("a11y")
        assertThat(screen.getValue("untrusted").jsonPrimitive.content).isEqualTo("true")
        assertThat(screen.str("coord")).isEqualTo(coordText)
        assertThat(screen.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("text", "desc", "hint", "state", "cls", "vid", "act", "st")
            .inOrder()
        val quality = screen.getValue("quality").jsonObject
        assertThat(quality.getValue("nodes").jsonPrimitive.content).isEqualTo("1")
        assertThat(quality.getValue("interactive").jsonPrimitive.content).isEqualTo("1")
        assertThat(quality).doesNotContainKey("degenerate")
        assertThat(quality).doesNotContainKey("uncertain")
        val out =
            screen
                .getValue(
                    "windows",
                ).jsonArray
                .single()
                .jsonObject
                .getValue("kids")
                .jsonArray
                .single()
                .jsonObject
        assertThat(out.str("tag")).isEqualTo("btn")
        assertThat(out.getValue("bounds").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("100", "200", "300", "400")
            .inOrder()
        assertThat(out.getValue("at").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("200", "300")
            .inOrder()
    }

    @Test
    fun `visual fields replace a11y only fields`() {
        val model =
            model(
                ObserveMode.VISUAL,
                listOf(ScreenNode(tag = "text", text = "标题", bounds = Rect(0, 0, 100, 100))),
            )
        val screen = screenOf(ScreenJsonRenderer.render(model, coordText, null))
        assertThat(screen.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("text", "icon", "hint", "state", "act", "st")
            .inOrder()
        val quality = screen.getValue("quality").jsonObject
        assertThat(quality).doesNotContainKey("uncertain")
        assertThat(quality).doesNotContainKey("degenerate")
    }

    @Test
    fun `empty fields are omitted and cached flag is rendered`() {
        val model =
            ScreenModel(
                mode = ObserveMode.LLM,
                windows = listOf(ScreenWindow(app = "微信")),
                quality = ScreenQuality(nodes = 0, interactive = 0),
                cached = true,
            )
        val screen = screenOf(ScreenJsonRenderer.render(model, coordText, null))
        assertThat(screen.getValue("cached").jsonPrimitive.content).isEqualTo("true")
        assertThat(screen).doesNotContainKey("unknown")
    }

    @Test
    fun `suggestion is appended after json`() {
        val model =
            ScreenModel(
                mode = ObserveMode.A11Y,
                windows = listOf(ScreenWindow(app = "设置")),
                quality = ScreenQuality(nodes = 1, interactive = 0, suggest = ObserveMode.VISUAL),
            )
        val text = ScreenJsonRenderer.render(model, coordText, "改用 visual")
        assertThat(text).endsWith("\n改用 visual")
        val body = text.substringBeforeLast("\n")
        assertThat(
            screenOf(body)
                .getValue("quality")
                .jsonObject
                .getValue("suggest")
                .jsonPrimitive.content,
        ).isEqualTo("visual")
    }

    @Test
    fun `diff only renders non empty sections`() {
        val added = ScreenNode(tag = "btn", text = "新", bounds = Rect(0, 0, 10, 10))
        val removed = ScreenNode(tag = "text", desc = "旧", bounds = Rect(20, 20, 30, 30))
        val json =
            Json.parseToJsonElement(
                ScreenJsonRenderer.renderDiff(ObserveMode.A11Y, listOf(added), emptyList(), listOf(removed)),
            )
        val diff = json.jsonObject.getValue("diff").jsonObject
        assertThat(diff.str("mode")).isEqualTo("a11y")
        assertThat(
            diff
                .getValue("added")
                .jsonArray
                .single()
                .jsonObject
                .str("text"),
        ).isEqualTo("新")
        assertThat(diff).doesNotContainKey("changed")
        val removedNode =
            diff
                .getValue("removed")
                .jsonArray
                .single()
                .jsonObject
        assertThat(removedNode.str("desc")).isEqualTo("旧")
        assertThat(removedNode).doesNotContainKey("text")
    }

    private fun model(
        mode: ObserveMode,
        nodes: List<ScreenNode>,
    ): ScreenModel =
        ScreenModel(
            mode = mode,
            windows = listOf(ScreenWindow(app = "设置", page = "SettingsActivity", active = true, kids = nodes)),
            quality =
                ScreenQuality(
                    nodes = nodes.size,
                    interactive = nodes.count { it.act.isNotEmpty() },
                ),
        )

    private fun screenOf(text: String): JsonObject =
        Json
            .parseToJsonElement(text)
            .jsonObject
            .getValue("screen")
            .jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
