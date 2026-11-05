/**
 * 04 §6 项 7/8：超过阈值触发压缩并写 Baseline(COMPACTION)；策略链里
 * Handoff 失败由 Deterministic 兜底；连续失败超上限以 agent.compaction_failed 结束。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.runtime.compaction.DeterministicCompactor
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.ScriptedLlmProvider
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.testing.overridesOf
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CompactionTest {
    @Test
    fun `7 超阈值触发压缩并写 Baseline(COMPACTION)`() =
        runTest {
            val h =
                RuntimeHarness(
                    tools = setOf(FakeTool()),
                    llm = smallContextLlm(),
                    fakes = HarnessFakes(compactors = setOf(DeterministicCompactor(TestPromptRepository()))),
                )
            h.llm.say("好的")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val baseline =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<Baseline>()
                    .single()
            assertThat(baseline.reason).isEqualTo(BaselineReason.COMPACTION)
            assertThat(baseline.coveredToSeq).isAtLeast(1L)
        }

    @Test
    fun `8a Handoff 失败由 Deterministic 兜底`() =
        runTest {
            val h =
                RuntimeHarness(
                    tools = setOf(FakeTool()),
                    llm = smallContextLlm(),
                    fakes =
                        HarnessFakes(
                            compactors =
                                setOf(
                                    FailingCompactor("compaction.handoff"),
                                    DeterministicCompactor(TestPromptRepository()),
                                ),
                        ),
                )
            h.llm.say("好的")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val baselines = h.store.payloadsOf(thread).filterIsInstance<Baseline>()
            assertThat(baselines.single().reason).isEqualTo(BaselineReason.COMPACTION)
        }

    @Test
    fun `8b 连续失败超上限以 compaction_failed 结束`() =
        runTest {
            val h =
                RuntimeHarness(
                    tools = setOf(FakeTool()),
                    llm = smallContextLlm(),
                    fakes = HarnessFakes(compactors = setOf(FailingCompactor("compaction.handoff"))),
                    configOverrides = overridesOf("agent", "compaction", "maxConsecutiveFailures" to 0),
                )
            h.llm.say("好的")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.FAILED)
            assertThat(finished.errorCode).isEqualTo(AgentErrorCodes.COMPACTION_FAILED)
        }

    private fun smallContextLlm(): ScriptedLlmProvider =
        ScriptedLlmProvider(profile = ScriptedLlmProvider.TEST_PROFILE.copy(contextWindow = 30))

    /** 永远失败的压缩策略。 */
    private class FailingCompactor(
        override val id: String,
    ) : Compactor {
        override suspend fun compact(input: CompactionInput): Outcome<CompactionResult> =
            Outcome.Err(AppError(code = "compaction.stub_failed", message = "stub"))
    }
}
