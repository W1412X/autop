package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ProbeVerdict
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** [DefaultModelProbe] 单测：文本连通、工具三态、错误与超时映射、请求输出上限（docs/tech/03 §3）。 */
internal class DefaultModelProbeTest {
    @Test
    fun healthyProviderSupportsTextAndTools() {
        runTest {
            val provider = FakeLlmProvider(profile()) { request -> answerFor(request) }

            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.text.ok).isTrue()
            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.SUPPORTED)
        }
    }

    @Test
    fun badRequestOnToolsWhileTextWorksIsUnsupported() {
        runTest {
            val provider =
                FakeLlmProvider(profile()) { request ->
                    if (request.tools.isEmpty()) completedEvent() else throw badRequest()
                }

            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.text.ok).isTrue()
            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.UNSUPPORTED)
        }
    }

    @Test
    fun answerWithoutToolCallIsInconclusive() {
        runTest {
            val provider = FakeLlmProvider(profile()) { completedEvent() }

            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.text.ok).isTrue()
            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.INCONCLUSIVE)
        }
    }

    @Test
    fun failedTextProbeLeavesToolsInconclusive() {
        runTest {
            val provider = FakeLlmProvider(profile()) { throw badRequest() }

            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.text.verdict).isEqualTo(ProbeVerdict.UNSUPPORTED)
            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.INCONCLUSIVE)
        }
    }

    @Test
    fun hangingProviderIsReportedAsTimeout() {
        runTest {
            val provider =
                FakeLlmProvider(profile()) {
                    delay(HANG_DELAY_MS)
                    completedEvent()
                }
            val config = FakeConfigProvider(probeTimeoutOverrides())

            val report = probe(factory("openai", provider), config).probe(profile())

            assertThat(report.text.error?.code).isEqualTo(LlmErrorCodes.TIMEOUT)
        }
    }

    @Test
    fun unknownProtocolIsNotConfigured() {
        runTest {
            val provider = FakeLlmProvider(profile()) { completedEvent() }

            val report = probe(factory("openai", provider)).probe(profile(protocol = "unknown"))

            assertThat(report.text.error?.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
            assertThat(report.tools.error?.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    @Test
    fun probesUseProfileMaxOutputTokensAndTextThenToolsRequests() {
        runTest {
            val provider = FakeLlmProvider(profile()) { request -> answerFor(request) }

            probe(factory("openai", provider)).probe(profile())

            assertThat(provider.requests.map { it.maxOutputTokens }).containsExactly(MAX_OUTPUT, MAX_OUTPUT).inOrder()
            assertThat(provider.requests[0].tools).isEmpty()
            assertThat(provider.requests[1].tools.map { it.name }).containsExactly("echo")
            assertThat(
                (
                    provider.requests[0]
                        .messages
                        .single()
                        .blocks
                        .single() as Block.Text
                ).text,
            ).isNotEmpty()
        }
    }

    private fun answerFor(request: com.example.autop.llm.LlmRequest) =
        if (request.tools.isEmpty()) completedEvent() else echoToolCompletedEvent()

    private fun badRequest(): LlmException =
        LlmException(AppError(code = LlmErrorCodes.BAD_REQUEST, message = "tools not supported"))

    private fun probe(
        factory: FakeLlmProviderFactory,
        config: FakeConfigProvider = FakeConfigProvider(),
    ): DefaultModelProbe = DefaultModelProbe(setOf(factory), config, FakeClock())

    private fun factory(
        protocolId: String,
        provider: FakeLlmProvider,
    ): FakeLlmProviderFactory = FakeLlmProviderFactory(protocolId) { provider }

    private fun profile(protocol: String = "openai"): ModelProfile =
        ModelProfile(
            id = "probe",
            displayName = "probe",
            protocol = protocol,
            baseUrl = "https://api.example.com",
            apiKeyRef = "secret:model:probe",
            model = "model",
            contextWindow = 8192,
            maxOutputTokens = MAX_OUTPUT,
        )

    private fun probeTimeoutOverrides() =
        Json.parseToJsonElement("""{"llm":{"probe":{"timeoutMs":$TIMEOUT_MS}}}""").jsonObject

    private companion object {
        const val MAX_OUTPUT = 4096
        const val TIMEOUT_MS = 50L
        const val HANG_DELAY_MS = 60_000L
    }
}
