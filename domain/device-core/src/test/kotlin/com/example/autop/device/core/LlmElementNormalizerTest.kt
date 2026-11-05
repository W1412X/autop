/**
 * llm 元素后处理（docs/design/observation-modes-coordinate-actions §4.5）：
 * 坐标格式换算、局部区域映射、过滤、去重、tag 推断与包含关系建树。
 */
package com.example.autop.device.core

import com.example.autop.device.Rect
import com.example.autop.llm.CoordSpace
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

class LlmElementNormalizerTest {
    private val tags = setOf("text", "btn", "input", "icon", "list")

    @Test
    fun `yxyx order is converted to xyxy`() {
        val nodes = normalize(elements(element(box = listOf(10, 20, 30, 40), tag = "btn")), order = BboxOrder.YXYX)
        assertThat(nodes.single().bounds).isEqualTo(Rect(20, 10, 40, 30))
    }

    @Test
    fun `pixel coordinates are scaled to 0-1000`() {
        val nodes =
            normalize(
                elements(element(box = listOf(0, 0, 250, 500), tag = "text")),
                space = CoordSpace.IMAGE_PIXELS,
                imageWidth = 500,
                imageHeight = 1000,
            )
        assertThat(nodes.single().bounds).isEqualTo(Rect(0, 0, 500, 500))
    }

    @Test
    fun `region is remapped back to full screen`() {
        val nodes =
            normalize(
                elements(element(box = listOf(0, 0, 1000, 1000), tag = "text")),
                region = Rect(100, 200, 300, 600),
            )
        assertThat(nodes.single().bounds).isEqualTo(Rect(100, 200, 300, 600))
    }

    @Test
    fun `invalid viewport and tiny elements are dropped`() {
        val nodes =
            normalize(
                elements(
                    element(box = listOf(100, 100, 100, 200), tag = "text"),
                    element(box = listOf(0, 0, 1, 1), tag = "text"),
                ),
                minAreaRatio = 0.001,
            )
        assertThat(nodes).isEmpty()
    }

    @Test
    fun `unknown tag is inferred and unknown words are dropped`() {
        val nodes =
            normalize(
                elements(
                    element(box = listOf(0, 0, 200, 100), tag = "mystery", act = listOf("click", "longpress")),
                ),
            )
        val node = nodes.single()
        assertThat(node.tag).isEqualTo("btn")
        assertThat(node.act).containsExactly("click")
        assertThat(node.at).isNotNull()
    }

    @Test
    fun `duplicates keep the longer text`() {
        val nodes =
            normalize(
                elements(
                    element(box = listOf(0, 0, 100, 100), tag = "text", text = "短"),
                    element(box = listOf(0, 0, 100, 100), tag = "text", text = "更长的文字"),
                ),
            )
        assertThat(nodes).hasSize(1)
        assertThat(nodes.single().text).isEqualTo("更长的文字")
    }

    @Test
    fun `containment builds tree with reading order`() {
        val nodes =
            normalize(
                elements(
                    element(box = listOf(0, 0, 500, 500), tag = "list", act = listOf("scroll")),
                    element(box = listOf(10, 10, 200, 100), tag = "text", text = "第二"),
                    element(box = listOf(10, 200, 200, 300), tag = "text", text = "第一"),
                    element(box = listOf(600, 0, 700, 100), tag = "text", text = "根"),
                ),
            )
        assertThat(nodes.map { it.tag }).containsExactly("list", "text").inOrder()
        assertThat(nodes.single { it.tag == "text" }.text).isEqualTo("根")
        val list = nodes.single { it.tag == "list" }
        assertThat(list.kids.map { it.text }).containsExactly("第二", "第一").inOrder()
    }

    @Test
    fun `missing elements returns null`() {
        assertThat(LlmElementNormalizer.normalize(JsonObject(emptyMap()), config())).isNull()
    }

    private fun normalize(
        params: JsonObject,
        space: CoordSpace = CoordSpace.NORMALIZED_1000,
        order: BboxOrder = BboxOrder.XYXY,
        imageWidth: Int = 1000,
        imageHeight: Int = 1000,
        region: Rect? = null,
        minAreaRatio: Double = 0.0001,
    ): List<com.example.autop.device.ScreenNode> =
        LlmElementNormalizer
            .normalize(
                params,
                LlmNormalizerConfig(
                    space = space,
                    order = order,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    region = region,
                    maxElements = 120,
                    minAreaRatio = minAreaRatio,
                    dupIou = 0.7,
                    tags = tags,
                ),
            ).orEmpty()

    private fun config(): LlmNormalizerConfig =
        LlmNormalizerConfig(
            space = CoordSpace.NORMALIZED_1000,
            order = BboxOrder.XYXY,
            imageWidth = 1000,
            imageHeight = 1000,
            maxElements = 120,
            minAreaRatio = 0.0001,
            dupIou = 0.7,
            tags = tags,
        )

    private fun elements(vararg items: JsonObject): JsonObject =
        buildJsonObject {
            putJsonArray("elements") { items.forEach { add(it) } }
        }

    private fun element(
        box: List<Int>,
        tag: String,
        text: String? = null,
        act: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("tag", tag)
            text?.let { put("text", it) }
            if (act.isNotEmpty()) {
                put("act", buildJsonArray { act.forEach { add(it) } })
            }
            put("box", buildJsonArray { box.forEach { add(it) } })
        }
}
