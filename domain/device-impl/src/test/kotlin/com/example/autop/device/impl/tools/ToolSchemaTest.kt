package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.core.testing.FakePromptRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
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
                "dismiss_popup",
                "restart_app",
                "open_app",
                "open_uri",
                "intent",
                "wait",
                "list_apps",
                "clipboard_get",
                "clipboard_set",
                "read_notifications",
            )
    }
}
