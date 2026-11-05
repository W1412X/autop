/**
 * DI 装配（docs/tech/04 §3.1/§3.4/§3.6/§3.9/§3.10）：三个 Hilt 模块的 @Provides
 * 产出与稳定 id（N12：public object + public 方法供 Hilt 生成类调用）。
 */
package com.example.autop.agent.runtime.di

import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.agent.runtime.testing.FakeLlmGateway
import com.example.autop.agent.runtime.testing.ScriptedLlmProvider
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiModulesTest {
    private val prompts = TestPromptRepository()

    @Test
    fun `AgentRuntimeModule 装配控制工具与检索端口`() =
        runTest {
            val recall = AgentRuntimeModule.provideThreadRecall(emptySet<ThreadRecallRetriever>(), FakeConfigProvider())
            val tools =
                listOf(
                    AgentRuntimeModule.provideUpdatePlanTool(),
                    AgentRuntimeModule.provideFinishTool(),
                    AgentRuntimeModule.provideAskUserTool(prompts),
                    AgentRuntimeModule.provideNoteTool(),
                    AgentRuntimeModule.provideHistoryRecallTool(recall),
                )

            assertThat(tools.map { it.id })
                .containsExactly("tool.update_plan", "tool.finish", "tool.ask_user", "tool.note", "tool.history_recall")
            assertThat(AgentRuntimeModule.provideNoRetrievers()).isEmpty()
            // 无检索器时融合结果为空（不报错）
            assertThat(recall.search(ThreadId("t1"), "关键词", 3)).isEmpty()
        }

    @Test
    fun `AgentContextModule 装配六个贡献者`() {
        val contributors =
            listOf(
                AgentContextModule.provideRoleContributor(prompts),
                AgentContextModule.provideSafetyContributor(prompts),
                AgentContextModule.provideToolGuideContributor(prompts),
                AgentContextModule.provideRunModeContributor(prompts),
                AgentContextModule.provideTriggerContributor(prompts),
                AgentContextModule.provideGoalContributor(prompts),
            )

        assertThat(contributors.map { it.id })
            .containsExactly(
                "context.role",
                "context.safety",
                "context.tool_guide",
                "context.run_mode",
                "context.trigger",
                "context.goal",
            )
    }

    @Test
    fun `AgentHistoryModule 装配三条规则与两种压缩策略`() {
        val rules =
            listOf(
                AgentHistoryModule.provideObservationDegradeRule(prompts),
                AgentHistoryModule.provideImageRetentionRule(prompts),
                AgentHistoryModule.provideLongTextTruncateRule(prompts),
            )
        val compactors =
            listOf(
                AgentHistoryModule.provideHandoffCompactor(FakeLlmGateway(ScriptedLlmProvider()), prompts),
                AgentHistoryModule.provideDeterministicCompactor(prompts),
            )

        assertThat(rules.map { it.id })
            .containsExactly("history.observation_degrade", "history.image_retention", "history.long_text_truncate")
        assertThat(compactors.map { it.id }).containsExactly("compaction.handoff", "compaction.deterministic")
    }
}
