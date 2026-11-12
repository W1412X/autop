package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.core.testing.FakePromptRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** 17 个设备工具的 schema 都能由真实模板组装，且每个参数都有说明（docs/tech/04 §2.3）。 */
class ToolSchemaTest {
    private val prompts = FakePromptRepository()

    @Test
    fun `every device tool spec builds a schema from its prompt template`() {
        for (spec in DeviceToolSpecs.ALL) {
            val schema = ToolSchemaBuilder.build(spec, prompts)
            assertThat(schema.name).isEqualTo(spec.name)
            assertThat(schema.description).isNotEmpty()
        }
    }

    @Test
    fun `every parameter carries a description from template meta`() {
        for (spec in DeviceToolSpecs.ALL) {
            val schema = ToolSchemaBuilder.build(spec, prompts)
            val props = schema.parameters["properties"]?.jsonObject.orEmpty()
            for ((name, prop) in props) {
                assertThat(prop.jsonObject["description"]?.toString()).isNotEmpty()
                assertThat(name).isNotEmpty()
            }
        }
    }

    @Test
    fun `tool names are unique and match the documented set`() {
        val names = DeviceToolSpecs.ALL.map { it.name }
        assertThat(names).containsExactlyElementsIn(EXPECTED_NAMES)
    }

    @Test
    fun `screen action tools declare wait_ms and observe has no full`() {
        val screenActionTools =
            setOf(
                "tap",
                "tap_node",
                "input_text",
                "scroll",
                "scroll_node",
                "swipe",
                "global_action",
                "open_app",
                "restart_app",
                "intent",
            )
        for (spec in DeviceToolSpecs.ALL.filter { it.name in screenActionTools }) {
            val properties = spec.parameters["properties"]!!.jsonObject
            assertThat(properties.keys).contains("wait_ms")
        }
        val observe = DeviceToolSpecs.ALL.first { it.name == "observe" }
        val observeProps = observe.parameters["properties"]!!.jsonObject
        assertThat(observeProps.keys).doesNotContain("full")
    }

    @Test
    fun `intent kind enum contains uri`() {
        val intent = DeviceToolSpecs.intent
        val kind = intent.parameters["properties"]!!.jsonObject["kind"]!!.jsonObject
        val enum = kind["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(enum).contains("uri")
    }

    @Test
    fun `every device tool has a probe fixture (N198)`() {
        for (spec in DeviceToolSpecs.ALL) {
            assertThat(ToolProbeFixtures.names).contains(spec.name)
        }
    }

    private companion object {
        val EXPECTED_NAMES =
            listOf(
                "observe",
                "screenshot",
                "tap",
                "tap_node",
                "input_text",
                "scroll",
                "scroll_node",
                "swipe",
                "global_action",
                "restart_app",
                "open_app",
                "intent",
                "wait",
                "list_apps",
                "clipboard_get",
                "clipboard_set",
                "read_notifications",
            )
    }
}
