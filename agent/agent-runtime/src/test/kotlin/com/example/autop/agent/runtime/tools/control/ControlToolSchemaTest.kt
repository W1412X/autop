package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.RecallHit
import com.example.autop.agent.ThreadRecall
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakePromptRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** 5 个控制工具的 schema 都能由真实模板组装，参数都有说明，且有自测 fixture（N198）。 */
class ControlToolSchemaTest {
    private val prompts = FakePromptRepository()
    private val tools: List<Tool> =
        listOf(
            AskUserTool(prompts),
            UpdatePlanTool(),
            FinishTool(TestPromptRepository()),
            NoteTool(prompts),
            RuntimeStateTool(),
            HistoryRecallTool(EmptyRecall, TestPromptRepository()),
        )

    @Test
    fun `every control tool spec builds a schema from its prompt template`() {
        for (tool in tools) {
            val schema = ToolSchemaBuilder.build(tool.spec, prompts)
            assertThat(schema.name).isEqualTo(tool.spec.name)
            assertThat(schema.description).isNotEmpty()
        }
    }

    @Test
    fun `every parameter carries a description from template meta`() {
        for (tool in tools) {
            val schema = ToolSchemaBuilder.build(tool.spec, prompts)
            val props = schema.parameters["properties"]?.jsonObject.orEmpty()
            for ((name, prop) in props) {
                assertThat(prop.jsonObject["description"]?.toString()).isNotEmpty()
                assertThat(name).isNotEmpty()
            }
        }
    }

    @Test
    fun `every control tool has a probe fixture (N198)`() {
        for (tool in tools) {
            assertThat(ToolProbeFixtures.names).contains(tool.spec.name)
        }
    }

    /** 空检索替身（只用到 spec，不触发检索）。 */
    private object EmptyRecall : ThreadRecall {
        override suspend fun search(
            threadId: ThreadId,
            query: String,
            k: Int,
        ): List<RecallHit> = emptyList()
    }
}
