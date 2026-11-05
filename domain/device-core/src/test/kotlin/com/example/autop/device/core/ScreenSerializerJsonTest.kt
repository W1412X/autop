/**
 * 序列化集成（docs/design/observation-modes-coordinate-actions §3）：
 * 内容区坐标基准、窗口折叠/焦点、模式字段与质量建议。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ScreenSerializerJsonTest {
    @Test
    fun `bounds and at are rebased to content area`() {
        val screen =
            screen(
                contentArea = Rect(0, 120, 1080, 2320),
                screenHeight = 2400,
                nodeArgs =
                    NodeSpec().apply {
                        className = "android.widget.Button"
                        text = "确定"
                        bounds = Rect(300, 600, 780, 840)
                        flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                    },
            )
        val node = onlyNode(screen)
        assertThat(node.array("bounds")).containsExactly("277", "218", "722", "327").inOrder()
        assertThat(node.array("at")).containsExactly("500", "272").inOrder()
    }

    @Test
    fun `focused editable node is reported on window`() {
        val screen =
            screen(
                nodeArgs =
                    NodeSpec().apply {
                        className = "android.widget.EditText"
                        text = "你好"
                        bounds = Rect(0, 800, 1080, 900)
                        flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.EDITABLE or NodeFlags.FOCUSED
                    },
            )
        val window = windowOf(screen)
        assertThat(window.array("focus")).containsExactly("0", "416", "1000", "468").inOrder()
    }

    @Test
    fun `ime window collapses without nodes`() {
        val base = screen()
        val withIme =
            base.copy(
                windows =
                    base.windows +
                        TestScreens.window(2) {
                            type = WindowType.INPUT_METHOD
                            packageName = "com.input.method"
                            title = null
                            active = false
                            layer = 5
                            rootIdx = null
                        },
            )
        val windows =
            Json
                .parseToJsonElement(TestScreens.serialize(withIme).observation.text)
                .jsonObject
                .getValue("screen")
                .jsonObject
                .getValue("windows")
                .jsonArray
                .map { it.jsonObject }
        assertThat(windows).hasSize(2)
        val ime = windows.single { it["type"]?.jsonPrimitive?.content == "输入法" }
        assertThat(ime.getValue("collapsed").jsonPrimitive.content).isEqualTo("true")
        assertThat(ime).doesNotContainKey("kids")
    }

    @Test
    fun `visual mode carries icon and visual fields`() {
        val screen =
            screen(
                nodeArgs =
                    NodeSpec().apply {
                        className = "visual.Button"
                        text = "确定"
                        iconLabel = "勾"
                        bounds = Rect(0, 0, 500, 100)
                        flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                        visualConf = 0.3f
                    },
            )
        val node = onlyNode(screen, ObserveMode.VISUAL)
        assertThat(node.str("icon")).isEqualTo("勾")
        assertThat(node).doesNotContainKey("cls")
        assertThat(node).doesNotContainKey("vid")
        assertThat(node.array("st")).containsExactly("uncertain")
        val fields =
            jsonOf(screen, ObserveMode.VISUAL)
                .getValue("screen")
                .jsonObject
                .getValue("fields")
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertThat(fields).containsExactly("text", "icon", "hint", "state", "act", "st").inOrder()
    }

    @Test
    fun `empty interactive count suggests next enabled mode`() {
        val screen =
            screen(
                nodeArgs =
                    NodeSpec().apply {
                        className = "android.widget.TextView"
                        text = "纯文本"
                        bounds = Rect(0, 0, 500, 100)
                    },
            )
        val result =
            ScreenSerializer(TestScreens.rules, TestScreens.config())
                .serialize(
                    screen,
                    ObserveMode.A11Y,
                    { RedactionMode.NONE },
                    TestScreens.OWN_PACKAGE,
                    effectiveModes = listOf(ObserveMode.A11Y, ObserveMode.VISUAL, ObserveMode.LLM),
                )
        val jsonBody = result.observation.text.substringBeforeLast("\n")
        val quality =
            jsonOf(jsonBody)
                .getValue("screen")
                .jsonObject
                .getValue("quality")
                .jsonObject
        assertThat(quality.getValue("suggest").jsonPrimitive.content).isEqualTo("visual")
        assertThat(result.observation.text).endsWith("[device.observation.suggest_mode]visual")
    }

    private fun screen(
        contentArea: Rect? = null,
        screenHeight: Int = 1920,
        nodeArgs: NodeSpec = NodeSpec(),
    ): ScreenSnapshot {
        val window = TestScreens.window { title = "设置" }
        val root =
            TestScreens.node(1) {
                children = listOf(2)
                bounds = Rect(0, 0, 1080, screenHeight)
            }
        val node = nodeArgs.build(2).copy(parent = 1, childIndex = 0)
        return TestScreens.snapshot(
            windows = listOf(window),
            nodes = listOf(root, node),
        ) {
            this.contentArea = contentArea
            this.screenHeight = screenHeight
        }
    }

    private fun screen(): ScreenSnapshot =
        TestScreens.snapshot(
            windows = listOf(TestScreens.window { title = "设置" }),
            nodes =
                listOf(
                    TestScreens.node(1) {
                        children = listOf(2)
                        bounds = Rect(0, 0, 1080, 1920)
                    },
                    TestScreens.node(2) {
                        parent = 1
                        text = "内容"
                    },
                ),
        )

    private fun onlyNode(
        screen: ScreenSnapshot,
        mode: ObserveMode = ObserveMode.A11Y,
    ): JsonObject =
        jsonOf(screen, mode)
            .getValue("screen")
            .jsonObject
            .getValue("windows")
            .jsonArray
            .single()
            .jsonObject
            .getValue("kids")
            .jsonArray
            .single()
            .jsonObject

    private fun windowOf(screen: ScreenSnapshot): JsonObject =
        jsonOf(screen)
            .getValue("screen")
            .jsonObject
            .getValue("windows")
            .jsonArray
            .single()
            .jsonObject

    private fun jsonOf(
        screen: ScreenSnapshot,
        mode: ObserveMode = ObserveMode.A11Y,
    ): JsonObject = jsonOf(TestScreens.serialize(screen, mode = mode).observation.text)

    private fun jsonOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.array(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}
