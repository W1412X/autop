package com.example.autop.llm.anthropic

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeSecretStore
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.http.RetryConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test

/** [AnthropicModelDirectory] 单测（N164）：路径/鉴权头、`data[].id` 解析与错误映射。 */
internal class AnthropicModelDirectoryTest {
    @Test
    fun listModelsParsesDataIds() {
        withServer { server ->
            server.enqueue(modelsResponse("claude-sonnet-4-5", "claude-haiku-4-5"))
            val outcome = directory().listModels(profile(server.url("/").toString()))
            val ids = outcome.unwrap()
            assertThat(ids).containsExactly("claude-sonnet-4-5", "claude-haiku-4-5").inOrder()
        }
    }

    @Test
    fun requestUsesModelsPathAndApiKeyHeaders() {
        withServer { server ->
            server.enqueue(modelsResponse("m1"))
            directory().listModels(profile(server.url("/").toString()))
            val recorded = server.takeRequest()
            assertThat(recorded.url.encodedPath).isEqualTo("/v1/models")
            assertThat(recorded.method).isEqualTo("GET")
            assertThat(recorded.headers["x-api-key"]).isEqualTo(API_KEY)
            assertThat(recorded.headers["anthropic-version"]).isEqualTo(API_VERSION)
        }
    }

    @Test
    fun httpErrorMapsToAuth() {
        withServer { server ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .body("""{"error":"bad key"}""")
                    .build(),
            )
            val outcome = directory().listModels(profile(server.url("/").toString()))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.AUTH)
        }
    }

    @Test
    fun missingApiKeyMapsToAuth() {
        withServer { server ->
            val outcome =
                directory(secrets = FakeSecretStore())
                    .listModels(profile(server.url("/").toString()))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.AUTH)
        }
    }

    private fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            server.start()
            runBlocking { block(server) }
        } finally {
            server.close()
        }
    }

    private fun directory(
        secrets: SecretStore = FakeSecretStore(mapOf(API_KEY_REF to API_KEY)),
    ): AnthropicModelDirectory =
        AnthropicModelDirectory(
            context =
                AnthropicContext(
                    client = OkHttpClient(),
                    retryConfig =
                        RetryConfig(
                            maxAttempts = 1,
                            baseDelayMs = 0,
                            maxDelayMs = 0,
                            retryStatus = emptySet(),
                        ),
                    secrets = secrets,
                    mapper = AnthropicRequestMapper(FakeImageLoader(), FakePrompts()),
                    quirksDefaults = AnthropicQuirksDefaults(emptyList(), 1024, API_VERSION),
                    dispatchers = TestDispatcherProvider(Dispatchers.IO),
                    clock = FakeClock(),
                ),
        )

    private fun profile(baseUrl: String): ModelProfile =
        ModelProfile(
            id = PROFILE_ID,
            displayName = "test",
            protocol = PROTOCOL_ID,
            baseUrl = baseUrl,
            apiKeyRef = API_KEY_REF,
            model = "claude-sonnet-4-5",
            contextWindow = 200000,
            maxOutputTokens = 8192,
        )

    private fun modelsResponse(vararg ids: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .body("""{"data":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""")
            .build()

    private fun <T> Outcome<T>.unwrap(): T = (this as Outcome.Ok<T>).value

    private companion object {
        const val PROFILE_ID = "p-list"
        const val API_KEY_REF = "secret:model:p-list"
        const val API_KEY = "test-key"
        const val API_VERSION = "2023-06-01"
    }
}
