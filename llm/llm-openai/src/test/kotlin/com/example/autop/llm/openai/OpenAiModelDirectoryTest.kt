package com.example.autop.llm.openai

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.core.testing.FakeSecretStore
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.http.RetryConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test

/** [OpenAiModelDirectory] 单测（N164）：路径/鉴权头、`data[].id` 解析与错误映射。 */
internal class OpenAiModelDirectoryTest {
    @Test
    fun listModelsParsesDataIds() {
        withServer { server ->
            server.enqueue(modelsResponse("gpt-4o", "gpt-4o-mini"))
            val outcome = directory().listModels(profile(server.url("/").toString()))
            val ids = outcome.unwrap()
            assertThat(ids).containsExactly("gpt-4o", "gpt-4o-mini").inOrder()
        }
    }

    @Test
    fun requestUsesModelsPathAndBearer() {
        withServer { server ->
            server.enqueue(modelsResponse("m1"))
            directory().listModels(profile(server.url("/").toString()))
            val recorded = server.takeRequest()
            assertThat(recorded.url.encodedPath).isEqualTo("/models")
            assertThat(recorded.method).isEqualTo("GET")
            assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer $API_KEY")
        }
    }

    @Test
    fun modelsPathQuirkOverridesDefault() {
        withServer { server ->
            server.enqueue(modelsResponse("m1"))
            val baseUrl = server.url("/").toString()
            directory().listModels(profile(baseUrl, quirks = mapOf("modelsPath" to "/v2/list")))
            val recorded = server.takeRequest()
            assertThat(recorded.url.encodedPath).isEqualTo("/v2/list")
        }
    }

    @Test
    fun httpErrorMapsToAuth() {
        withServer { server ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(401)
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

    @Test
    fun unparseableBodyMapsToBadResponse() {
        withServer { server ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("not json")
                    .build(),
            )
            val outcome = directory().listModels(profile(server.url("/").toString()))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.BAD_RESPONSE)
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
    ): OpenAiModelDirectory =
        OpenAiModelDirectory(
            context =
                OpenAiContext(
                    client = OkHttpClient(),
                    retryConfig =
                        RetryConfig(
                            maxAttempts = 1,
                            baseDelayMs = 0,
                            maxDelayMs = 0,
                            retryStatus = emptySet(),
                        ),
                    secrets = secrets,
                    mapper = OpenAiRequestMapper(FakeImageLoader(), FakePrompts()),
                    quirksDefaults = OpenAiQuirksDefaults(emptyList(), 1024),
                ),
        )

    private fun profile(
        baseUrl: String,
        quirks: Map<String, String> = emptyMap(),
    ): ModelProfile =
        ModelProfile(
            id = PROFILE_ID,
            displayName = "test",
            protocol = PROTOCOL_ID,
            baseUrl = baseUrl,
            apiKeyRef = API_KEY_REF,
            model = "gpt-4o",
            contextWindow = 8192,
            maxOutputTokens = 256,
            quirks = quirks,
        )

    private fun modelsResponse(vararg ids: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .body("""{"object":"list","data":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""")
            .build()

    private fun <T> Outcome<T>.unwrap(): T = (this as Outcome.Ok<T>).value

    private companion object {
        const val PROFILE_ID = "p-list"
        const val API_KEY_REF = "secret:model:p-list"
        const val API_KEY = "test-key"
    }
}
