/**
 * 回合级重试（agent-harness-overhaul WP6.11）：可重试的流式中途失败重试并发
 * SampleRetrying；context_exceeded 不由采样器重试；超过 maxStreamRetries 放弃。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.RunRequest
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.NoopLogger
import com.example.autop.agent.runtime.testing.ScriptedLlmProvider
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.AppError
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.LlmErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TurnSamplerTest {
    @Test
    fun `retryable mid stream failure retries and emits SampleRetrying`() =
        runTest {
            val provider =
                ScriptedLlmProvider()
                    .fail(AppError(code = LlmErrorCodes.NETWORK, message = "boom", retryable = true))
                    .say("好")
            val fixture = fixture(this, provider)

            val sampled = fixture.sample(provider)

            assertThat(sampled).isInstanceOf(Sampled.Ok::class.java)
            assertThat(provider.requests).hasSize(2)
            assertThat(
                fixture.controller.events.replayCache
                    .filterIsInstance<AgentEvent.SampleRetrying>(),
            ).hasSize(1)
        }

    @Test
    fun `context exceeded is not retried by sampler`() =
        runTest {
            val provider =
                ScriptedLlmProvider()
                    .fail(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = "too long"))
                    .fail(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = "too long"))
            val fixture = fixture(this, provider)

            val sampled = fixture.sample(provider)

            // 每次 streamWithRetry 只尝试一次：一次压缩重试，不做采样器内部重试。
            assertThat(sampled).isInstanceOf(Sampled.Failed::class.java)
            assertThat(provider.requests).hasSize(2)
        }

    @Test
    fun `gives up after maxStreamRetries`() =
        runTest {
            val provider =
                ScriptedLlmProvider().apply {
                    repeat(5) { fail(AppError(code = LlmErrorCodes.NETWORK, message = "boom", retryable = true)) }
                }
            val fixture = fixture(this, provider)

            val sampled = fixture.sample(provider)

            assertThat(sampled).isInstanceOf(Sampled.Failed::class.java)
            assertThat(provider.requests).hasSize(3)
        }

    private suspend fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        provider: ScriptedLlmProvider,
    ): Fixture {
        val prompts = TestPromptRepository()
        val store = InMemoryThreadStore(FakeClock(1_000L))
        val config = FakeConfigProvider()
        val thread = store.createThread(null, null)
        val req =
            RunRequest(
                runId = RunId("run-1"),
                threadId = thread,
                input = emptyList(),
                goal = null,
                vars = emptyMap(),
                config = config.current(),
                toolFilter = null,
            )
        val controller = RunController(req, store, scope, 8, prompts)
        val sampler = TurnSampler(req, controller, prompts, NoopLogger)
        sampler.bindProfile(provider)
        return Fixture(sampler, controller)
    }

    private class Fixture(
        private val sampler: TurnSampler,
        val controller: RunController,
    ) {
        suspend fun sample(provider: ScriptedLlmProvider): Sampled =
            sampler.sample(
                provider,
                emptyList(),
                false,
                system = { emptyList() },
                render = { emptyList() },
                compact = { CompactResult.Continue },
            )
    }
}
