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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [DefaultModelProbe] 单测：三项烟测、错误/超时映射与内置探测图（docs/tech/03 §3）。 */
internal class DefaultModelProbeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun allProbesReturnOkForHealthyProvider() {
        runTest {
            val provider = FakeLlmProvider(profile()) { echoToolCompletedEvent() }
            val report = probe(factory("openai", provider)).probe(profile())
            assertThat(report.text.ok).isTrue()
            assertThat(report.tools.ok).isTrue()
            assertThat(report.vision.ok).isTrue()
            assertThat(provider.requests).hasSize(PROBE_COUNT)
        }
    }

    @Test
    fun missingFactoryFailsAllProbes() {
        runTest {
            val report = probe(factory("openai", FakeLlmProvider(profile()))).probe(profile(protocol = "unknown"))
            assertThat(report.text.error?.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
            assertThat(report.tools.error?.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
            assertThat(report.vision.error?.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    @Test
    fun providerErrorPropagatesToResults() {
        runTest {
            val failing =
                FakeLlmProvider(profile()) {
                    throw LlmException(AppError(code = LlmErrorCodes.AUTH, message = "bad key"))
                }
            val report = probe(factory("openai", failing)).probe(profile())
            assertThat(report.text.error?.code).isEqualTo(LlmErrorCodes.AUTH)
            assertThat(report.vision.error?.code).isEqualTo(LlmErrorCodes.AUTH)
        }
    }

    @Test
    fun hangingProviderReportsTimeout() {
        runTest {
            val hanging =
                FakeLlmProvider(profile()) {
                    delay(HANG_DELAY_MS)
                    completedEvent()
                }
            val config = FakeConfigProvider(probeTimeoutOverrides())
            val report = probe(factory("openai", hanging), config).probe(profile())
            assertThat(report.text.error?.code).isEqualTo(LlmErrorCodes.TIMEOUT)
        }
    }

    @Test
    fun toolsProbeWithoutEchoToolCallIsInconclusive() {
        runTest {
            val provider = FakeLlmProvider(profile())
            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.INCONCLUSIVE)
            assertThat(report.text.ok).isTrue()
        }
    }

    @Test
    fun toolsRequestRejectedWhileTextWorksIsUnsupported() {
        runTest {
            val provider =
                FakeLlmProvider(profile()) { request ->
                    if (request.tools.isNotEmpty()) {
                        throw LlmException(AppError(code = LlmErrorCodes.BAD_REQUEST, message = "tools not supported"))
                    }
                    completedEvent()
                }
            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.UNSUPPORTED)
        }
    }

    @Test
    fun toolsProbeServerErrorIsInconclusive() {
        runTest {
            val provider =
                FakeLlmProvider(profile()) { request ->
                    if (request.tools.isNotEmpty()) {
                        throw LlmException(AppError(code = LlmErrorCodes.SERVER, message = "overloaded"))
                    }
                    completedEvent()
                }
            val report = probe(factory("openai", provider)).probe(profile())

            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.INCONCLUSIVE)
        }
    }

    @Test
    fun badRequestWhenTextAlsoFailsIsInconclusiveForTools() {
        runTest {
            val failing =
                FakeLlmProvider(profile()) {
                    throw LlmException(AppError(code = LlmErrorCodes.BAD_REQUEST, message = "bad model name"))
                }
            val report = probe(factory("openai", failing)).probe(profile())

            assertThat(report.text.verdict).isEqualTo(ProbeVerdict.UNSUPPORTED)
            assertThat(report.tools.verdict).isEqualTo(ProbeVerdict.INCONCLUSIVE)
        }
    }

    @Test
    fun everyProbeUsesProfileMaxOutputTokensAndEchoHasRequiredText() {
        runTest {
            val provider = FakeLlmProvider(profile()) { echoToolCompletedEvent() }
            probe(factory("openai", provider)).probe(profile())

            assertThat(provider.requests.map { it.maxOutputTokens }.distinct()).containsExactly(MAX_OUTPUT)
            val echo = provider.requests.flatMap { it.tools }.single()
            assertThat(echo.parameters["required"].toString()).contains("text")
        }
    }

    @Test
    fun visionProbeCarriesLocalPngImage() {
        runTest {
            var image: Block.Image? = null
            var bytes: ByteArray? = null
            val provider =
                FakeLlmProvider(profile()) { request ->
                    request.messages.single().blocks.filterIsInstance<Block.Image>().singleOrNull()?.let { block ->
                        image = block
                        bytes = File(block.fileRef).readBytes()
                    }
                    completedEvent()
                }
            val imageDir = folder.newFolder()
            probe(factory("openai", provider), imageDir = imageDir).probe(profile())
            val captured = requireNotNull(image)
            val png = requireNotNull(bytes)
            assertThat(png.copyOf(PNG_SIGNATURE.size).toList()).isEqualTo(pngSignature().toList())
            assertThat(captured.mime).isEqualTo(PROBE_IMAGE_MIME)
            assertThat(captured.width).isEqualTo(PROBE_IMAGE_EDGE)
            assertThat(captured.height).isEqualTo(PROBE_IMAGE_EDGE)
            assertThat(imageDir.listFiles()).isEmpty()
        }
    }

    private fun probe(
        factory: FakeLlmProviderFactory,
        config: FakeConfigProvider = FakeConfigProvider(),
        imageDir: File = folder.root,
    ): DefaultModelProbe = DefaultModelProbe(setOf(factory), config, imageDir, FakeClock())

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
        const val PROBE_COUNT = 3
        const val MAX_OUTPUT = 4096
        const val PROBE_IMAGE_MIME = "image/png"
        const val PROBE_IMAGE_EDGE = 8
        const val TIMEOUT_MS = 50
        const val HANG_DELAY_MS = 60_000L
        val PNG_SIGNATURE = pngSignature()
    }
}
