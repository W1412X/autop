package com.example.autop.llm.http

import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelDirectoryFactory
import com.example.autop.llm.ModelProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [DefaultModelDirectory] 单测（N164）：按 protocolId 分发与无匹配扩展点的错误。 */
internal class DefaultModelDirectoryTest {
    @Test
    fun dispatchesByProtocolId() {
        runBlocking {
            val openAi = RecordingDirectory("openai-ok")
            val anthropic = RecordingDirectory("anthropic-ok")
            val aggregate =
                DefaultModelDirectory(
                    setOf(
                        RecordingFactory("openai", openAi),
                        RecordingFactory("anthropic", anthropic),
                    ),
                )
            val outcome = aggregate.listModels(profile("anthropic"))
            assertThat(outcome.unwrap()).containsExactly("anthropic-ok")
            assertThat(openAi.calls).isEqualTo(0)
            assertThat(anthropic.calls).isEqualTo(1)
        }
    }

    @Test
    fun unknownProtocolMapsToNotConfigured() {
        runBlocking {
            val aggregate = DefaultModelDirectory(setOf(RecordingFactory("openai", RecordingDirectory("ok"))))
            val outcome = aggregate.listModels(profile("gemini"))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    private fun profile(protocol: String): ModelProfile =
        ModelProfile(
            id = "p1",
            displayName = "test",
            protocol = protocol,
            baseUrl = "https://api.example.com/v1",
            apiKeyRef = "secret:model:p1",
            model = "m1",
            contextWindow = 8192,
            maxOutputTokens = 256,
        )

    private fun <T> Outcome<T>.unwrap(): T = (this as Outcome.Ok<T>).value

    /** 固定返回一个 id 的假件（记录调用次数）。 */
    private class RecordingDirectory(
        private val id: String,
    ) : ModelDirectory {
        var calls = 0

        override suspend fun listModels(profile: ModelProfile): Outcome<List<String>> {
            calls += 1
            return Outcome.Ok(listOf(id))
        }
    }

    private class RecordingFactory(
        override val protocolId: String,
        private val directory: ModelDirectory,
    ) : ModelDirectoryFactory {
        override fun create(): ModelDirectory = directory
    }
}
