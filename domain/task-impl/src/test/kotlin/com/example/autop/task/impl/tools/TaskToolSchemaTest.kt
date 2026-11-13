package com.example.autop.task.impl.tools

import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.core.testing.FakePromptRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** 任务域工具的 schema 能由真实模板组装、参数有说明，且有自测 fixture（N198）。 */
class TaskToolSchemaTest {
    private val prompts = FakePromptRepository()
    private val specs =
        listOf(
            TaskToolSpecs.notifyUser,
            TaskToolSpecs.listTasks,
            TaskToolSpecs.createTask(),
            TaskToolSpecs.updateTask(),
        )

    @Test
    fun `every task spec builds a schema from its prompt template`() {
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
    fun `every task spec has a probe fixture (N198)`() {
        for (spec in specs) {
            assertThat(ToolProbeFixtures.names).contains(spec.name)
        }
    }

    @Test
    fun `update_task accepts the same fields as create_task plus the target`() {
        val create =
            TaskToolSpecs
                .createTask()
                .parameters["properties"]
                ?.jsonObject
                ?.keys
                .orEmpty()
        val update =
            TaskToolSpecs
                .updateTask()
                .parameters["properties"]
                ?.jsonObject
                ?.keys
                .orEmpty()
        assertThat(update - "task").isEqualTo(create)
    }
}
