/**
 * 压缩后 system 重新组装（agent-harness-overhaul WP4.7）：
 * `llm.context_exceeded` 走唯一压缩入口，重试用最新 system。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.ScriptedLlmProvider
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class TurnLoopCompactionTest {
    @Test
    fun `system is reassembled after compaction`() =
        runTest {
            val contributor = CountingContributor()
            val h = harness(contributor)
            h.llm
                .fail(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = "too long"))
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            assertThat(h.llm.requests).hasSize(2)
            val first = systemText(h, 0)
            val second = systemText(h, 1)
            assertThat(first).contains("轮次标记 1")
            assertThat(second).contains("轮次标记 2")
            assertThat(first).isNotEqualTo(second)
        }

    @Test
    fun `context exceeded uses compactNow and latest system`() =
        runTest {
            val contributor = CountingContributor()
            val h = harness(contributor)
            h.llm
                .fail(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = "too long"))
                .say("好")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            // 压缩走唯一入口并写 Baseline(COMPACTION)。
            val baseline =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<Baseline>()
                    .single()
            assertThat(baseline.reason).isEqualTo(BaselineReason.COMPACTION)
            // 重试请求用的是压缩后重新组装的最新 system。
            assertThat(systemText(h, 1)).contains("轮次标记 2")
        }

    private fun systemText(
        h: RuntimeHarness,
        index: Int,
    ): String =
        h.llm.requests[index]
            .system
            .joinToString("\n") { it.text }

    private fun harness(contributor: CountingContributor): RuntimeHarness {
        val overrides =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put(
                            "context",
                            buildJsonObject {
                                put("contributors", JsonArray(listOf(JsonPrimitive(contributor.id))))
                            },
                        )
                    },
                )
            }
        return RuntimeHarness(
            llm = ScriptedLlmProvider(),
            configOverrides = overrides,
            fakes =
                HarnessFakes(
                    compactors = setOf(OkCompactor()),
                    contributors = setOf(contributor),
                ),
        )
    }

    /** 每次组装返回不同文字，用于断言压缩后 system 重新组装。 */
    private class CountingContributor : ContextContributor {
        override val id: String = "context.test_counter"
        private var calls = 0

        override suspend fun contribute(req: ContextRequest): ContextPart {
            calls += 1
            return ContextPart(placement = Placement.SYSTEM_STATIC, text = "轮次标记 $calls", promptIds = emptyMap())
        }
    }

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
}
