package com.example.autop.task.impl.tools

import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.core.testing.FakePromptRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** notify_user 的 schema 能由真实模板组装、参数有说明，且有自测 fixture（N198）。 */
class TaskToolSchemaTest {
    private val prompts = FakePromptRepository()

    @Test
    fun `notify_user spec builds a schema from its prompt template`() {
        val schema = ToolSchemaBuilder.build(TaskToolSpecs.notifyUser, prompts)
        assertThat(schema.name).isEqualTo("notify_user")
        assertThat(schema.description).isNotEmpty()
    }

    @Test
    fun `every parameter carries a description from template meta`() {
        val schema = ToolSchemaBuilder.build(TaskToolSpecs.notifyUser, prompts)
        val props = schema.parameters["properties"]?.jsonObject.orEmpty()
        for ((name, prop) in props) {
            assertThat(prop.jsonObject["description"]?.toString()).isNotEmpty()
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `notify_user has a probe fixture (N198)`() {
        assertThat(ToolProbeFixtures.names).contains(TaskToolSpecs.notifyUser.name)
    }
}
