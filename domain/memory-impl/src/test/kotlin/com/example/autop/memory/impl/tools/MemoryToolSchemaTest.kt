package com.example.autop.memory.impl.tools

import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.agent.ToolSpec
import com.example.autop.core.testing.FakePromptRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** 4 个记忆工具的 schema 都能由真实模板组装，参数都有说明，且有自测 fixture（N198）。 */
class MemoryToolSchemaTest {
    private val prompts = FakePromptRepository()
    private val specs: List<ToolSpec> =
        listOf(
            MemoryToolSpecs.memoryList,
            MemoryToolSpecs.memoryRead,
            MemoryToolSpecs.memorySearch,
            MemoryToolSpecs.memoryWrite,
        )

    @Test
    fun `every memory tool spec builds a schema from its prompt template`() {
        for (spec in specs) {
            val schema = ToolSchemaBuilder.build(spec, prompts)
            assertThat(schema.name).isEqualTo(spec.name)
            assertThat(schema.description).isNotEmpty()
        }
    }

    @Test
    fun `every parameter carries a description from template meta`() {
        for (spec in specs) {
            val schema = ToolSchemaBuilder.build(spec, prompts)
            val props = schema.parameters["properties"]?.jsonObject.orEmpty()
            for ((name, prop) in props) {
                assertThat(prop.jsonObject["description"]?.toString()).isNotEmpty()
                assertThat(name).isNotEmpty()
            }
        }
    }

    @Test
    fun `every memory tool has a probe fixture (N198)`() {
        for (spec in specs) {
            assertThat(ToolProbeFixtures.names).contains(spec.name)
        }
    }
}
