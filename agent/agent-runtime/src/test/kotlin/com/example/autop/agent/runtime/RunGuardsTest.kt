/**
 * 压缩后上报 token 清零（agent-harness-overhaul WP4.3/4.7）：
 * 上报值超阈值触发压缩后，下一轮不再因旧上报值重复压缩。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.agent.RunRequest
import com.example.autop.agent.runtime.compaction.CompactionCoordinator
import com.example.autop.agent.runtime.guard.Budget
import com.example.autop.agent.runtime.guard.CircuitBreaker
import com.example.autop.agent.runtime.notes.RunNotes
import com.example.autop.agent.runtime.state.RuntimeStateTracker
import com.example.autop.agent.runtime.testing.FakePolicyEngine
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.NoopLogger
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.tools.ToolExecutor
import com.example.autop.agent.runtime.tools.ToolRouter
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RunGuardsTest {
    @Test
    fun `compaction resets reported tokens so next round does not compact again`() =
        runTest {
            val clock = FakeClock(1_000L)
            val prompts = TestPromptRepository()
            val store = InMemoryThreadStore(clock)
            val thread = store.createThread(null, null)
            val config = FakeConfigProvider().current()
            val req =
                RunRequest(
                    runId = RunId("run-1"),
                    threadId = thread,
                    input = listOf(Block.Text("hi")),
                    goal = null,
                    vars = emptyMap(),
                    config = config,
                    toolFilter = null,
                )
            val controller = RunController(req, store, this, 0, prompts)
            val engines =
                RunEngines(
                    budget = Budget(config[AgentConfig.BUDGET], clock),
                    breaker = CircuitBreaker(config[AgentConfig.BREAKER]),
                    compaction = CompactionCoordinator(store, setOf(OkCompactor()), req.runId),
                    notes = RunNotes(store, prompts) { it.length },
                    state = RuntimeStateTracker(emptyMap(), store, prompts, NoopLogger),
                    ports = RunPorts(FakeLlmGatewayNone, prompts, NoopLogger, null, null, null),
                )
            val router =
                ToolRouter(
                    emptyList(),
                    ToolExecutor(emptyList(), FakePolicyEngine(), null, prompts, clock, NoopLogger),
                    emptyList(),
                    prompts,
                )
            val guards = RunGuards(req, controller, HistoryRenderer(store, emptySet()), engines, router)
            guards.bindProfile(profileOf(contextWindow = 1_000))

            // 上报 900 token + 新增估算，超过阈值（1000 × 默认 ratio）。
            guards.noteReportedInput(Usage(inputTokens = 900, outputTokens = 0))
            guards.noteAdded(listOf(Block.Text("x".repeat(200))))
            assertThat(guards.overThreshold(emptyList(), emptyList(), emptyList())).isTrue()

            val compacted = guards.compact(emptyList(), initialContext = null)

            assertThat(compacted).isEqualTo(CompactResult.Continue)
            assertThat(guards.lastCompactionSucceeded).isTrue()
            // 清零后只看本地估算：下一轮不再压缩（WP4.3）。
            assertThat(guards.overThreshold(emptyList(), emptyList(), emptyList())).isFalse()
        }

    private fun profileOf(contextWindow: Int): com.example.autop.llm.ModelProfile =
        com.example.autop.llm.ModelProfile(
            id = "test",
            displayName = "test",
            protocol = "openai",
            baseUrl = "",
            apiKeyRef = "",
            model = "test",
            contextWindow = contextWindow,
            maxOutputTokens = 1000,
        )

    private class OkCompactor : Compactor {
        override val id: String = "compaction.deterministic"

        override suspend fun compact(input: CompactionInput): Outcome<CompactionResult> =
            Outcome.Ok(
                CompactionResult(
                    baselineMessages = listOf(Msg(Role.USER, listOf(Block.Text("摘要")))),
                    summaryText = "摘要",
                    coveredFromSeq = 0L,
                    coveredToSeq = 0L,
                ),
            )
    }

    private object FakeLlmGatewayNone : com.example.autop.llm.LlmGateway {
        override fun forDefault(): Outcome<com.example.autop.llm.LlmProvider> =
            Outcome.Err(
                com.example.autop.core.common
                    .AppError("llm.not_configured", "none"),
            )

        override fun forProfile(profileId: String): Outcome<com.example.autop.llm.LlmProvider> =
            Outcome.Err(
                com.example.autop.core.common
                    .AppError("llm.not_configured", "none"),
            )
    }
}
