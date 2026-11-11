/**
 * 内置上下文贡献者（docs/tech/04 §3.10）：固定模板贡献并记录版本、渲染失败返回 null、
 * run_mode 按 runMode 选模板、trigger/goal 无内容时不贡献。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextContributorsTest {
    private val prompts = TestPromptRepository()

    @Test
    fun `固定模板贡献者渲染并记录版本`() =
        runTest {
            val role = RoleContributor(prompts).contribute(req())
            val safety = SafetyContributor(prompts).contribute(req())
            val guide = ToolGuideContributor(prompts).contribute(req())

            assertThat(role?.placement).isEqualTo(Placement.SYSTEM_STATIC)
            assertThat(role?.promptIds).containsExactly("agent.system.role", 3)
            assertThat(role?.text).isNotEmpty()
            assertThat(safety?.promptIds).containsExactly("agent.system.safety", 1)
            assertThat(guide?.promptIds).containsExactly("agent.system.tool_guide", 13)
        }

    @Test
    fun `模板渲染失败返回 null`() =
        runTest {
            val part = RoleContributor(ErrPrompts).contribute(req())

            assertThat(part).isNull()
        }

    @Test
    fun `run_mode 按 runMode 选模板`() =
        runTest {
            val background = RunModeContributor(prompts).contribute(req(vars = mapOf("runMode" to "background")))
            val foreground = RunModeContributor(prompts).contribute(req(vars = emptyMap()))

            assertThat(background?.placement).isEqualTo(Placement.SYSTEM_DYNAMIC)
            assertThat(background?.promptIds).containsExactly("agent.system.run_mode.background", 1)
            assertThat(foreground?.promptIds).containsExactly("agent.system.run_mode.foreground", 1)
        }

    @Test
    fun `trigger 无触发不贡献有触发渲染细节`() =
        runTest {
            val none = TriggerContributor(prompts).contribute(req(vars = mapOf("other" to "x")))
            val vars = mapOf("trigger.source" to "通知", "trigger.time" to "9点")
            val part = TriggerContributor(prompts).contribute(req(vars = vars))

            assertThat(none).isNull()
            assertThat(part?.placement).isEqualTo(Placement.FIRST_USER)
            assertThat(part?.promptIds).containsExactly("agent.context.trigger", 1)
            assertThat(part?.text).contains("通知")
        }

    @Test
    fun `goal 空不贡献有目标才贡献`() =
        runTest {
            val contributor = GoalContributor(prompts)

            assertThat(contributor.contribute(req(goal = null))).isNull()
            assertThat(contributor.contribute(req(goal = "  "))).isNull()
            val part = contributor.contribute(req(goal = "订机票"))
            assertThat(part?.placement).isEqualTo(Placement.FIRST_USER)
            assertThat(part?.text).contains("订机票")
        }

    private fun req(
        goal: String? = null,
        vars: Map<String, String> = emptyMap(),
    ): ContextRequest =
        ContextRequest(
            runId = RunId("run-ctx"),
            threadId = ThreadId("t1"),
            config = FakeConfigProvider().current(),
            goal = goal,
            vars = vars,
        )

    /** 渲染总是失败的 PromptRepository。 */
    private object ErrPrompts : PromptRepository {
        override fun get(id: String): PromptTemplate = error("unused")

        override fun render(
            id: String,
            vars: Map<String, String>,
        ): Outcome<RenderedPrompt> = Outcome.Err(AppError(code = "test.render_failed", message = "render failed"))

        override fun all(): List<PromptTemplate> = emptyList()
    }
}
