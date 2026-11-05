package com.example.autop.llm.openai

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.core.testing.FakeSecretStore
import com.example.autop.llm.EmbeddingConfig
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test

/** [OpenAiEmbeddingProvider] 单测：批量切分、index 排序、维度发现与错误映射（docs/tech/03 §7）。 */
internal class OpenAiEmbeddingProviderTest {
    @Test
    fun embedSplitsBatchesAndMergesVectors() {
        withServer { server ->
            server.enqueue(embeddingsResponse(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)))
            server.enqueue(embeddingsResponse(floatArrayOf(5f, 6f)))
            val provider = provider(server.url("/").toString(), batchSize = 2)
            val outcome = provider.embed(listOf("a", "b", "c"))
            val vectors = outcome.unwrap()
            assertThat(vectors).hasSize(3)
            assertThat(vectors[2].toList()).containsExactly(5f, 6f).inOrder()
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun embedOrdersDataByIndex() {
        withServer { server ->
            server.enqueue(unorderedResponse())
            val vectors = provider(server.url("/").toString()).embed(listOf("a", "b")).unwrap()
            assertThat(vectors[0].toList()).containsExactly(1f, 2f).inOrder()
            assertThat(vectors[1].toList()).containsExactly(3f, 4f).inOrder()
        }
    }

    @Test
    fun dimensionDiscoveredFromFirstEmbed() {
        withServer { server ->
            server.enqueue(embeddingsResponse(floatArrayOf(1f, 2f, 3f)))
            val provider = provider(server.url("/").toString())
            assertThat(provider.dimension).isEqualTo(UNKNOWN_DIMENSION)
            provider.embed(listOf("a")).unwrap()
            assertThat(provider.dimension).isEqualTo(3)
        }
    }

    @Test
    fun configuredDimensionIsReported() {
        withServer { server ->
            val provider = provider(server.url("/").toString(), dimension = 768)
            assertThat(provider.dimension).isEqualTo(768)
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
            val outcome = provider(server.url("/").toString()).embed(listOf("a"))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.AUTH)
        }
    }

    @Test
    fun missingProfileMapsToNotConfigured() {
        withServer { server ->
            val outcome = provider(server.url("/").toString(), profileId = "missing").embed(listOf("a"))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    @Test
    fun missingApiKeyMapsToAuth() {
        withServer { server ->
            val secrets = FakeSecretStore()
            val outcome = provider(server.url("/").toString(), secrets = secrets).embed(listOf("a"))
            assertThat((outcome as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.AUTH)
        }
    }

    @Test
    fun requestUsesEmbeddingsPathAndBearer() {
        withServer { server ->
            server.enqueue(embeddingsResponse(floatArrayOf(1f)))
            provider(server.url("/").toString()).embed(listOf("hello"))
            val recorded = server.takeRequest()
            assertThat(recorded.url.encodedPath).isEqualTo("/embeddings")
            assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer $API_KEY")
            assertThat(recorded.body?.utf8().orEmpty()).contains(""""input":["hello"]""")
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

    private fun provider(
        baseUrl: String,
        profileId: String? = PROFILE_ID,
        dimension: Int? = null,
        batchSize: Int = 16,
        secrets: SecretStore = FakeSecretStore(mapOf(API_KEY_REF to API_KEY)),
    ): OpenAiEmbeddingProvider =
        OpenAiEmbeddingProvider(
            config =
                EmbeddingConfig(
                    provider = EMBEDDING_PROVIDER_ID,
                    profileId = profileId,
                    model = EMBED_MODEL,
                    dimension = dimension,
                    batchSize = batchSize,
                ),
            context =
                OpenAiEmbeddingContext(
                    client = OkHttpClient(),
                    secrets = secrets,
                    profiles = FakeModelProfileStore(listOf(profile(baseUrl))),
                    quirksDefaults = OpenAiQuirksDefaults(emptyList(), 1024),
                ),
        )

    private fun profile(baseUrl: String): ModelProfile =
        ModelProfile(
            id = PROFILE_ID,
            displayName = "embed",
            protocol = PROTOCOL_ID,
            baseUrl = baseUrl,
            apiKeyRef = API_KEY_REF,
            model = EMBED_MODEL,
            contextWindow = 8192,
            maxOutputTokens = 256,
        )

    private fun embeddingsResponse(vararg vectors: FloatArray): MockResponse =
        jsonResponse(vectors.mapIndexed { index, vector -> indexedItem(index, vector) })

    private fun unorderedResponse(): MockResponse =
        jsonResponse(listOf(indexedItem(1, floatArrayOf(3f, 4f)), indexedItem(0, floatArrayOf(1f, 2f))))

    private fun jsonResponse(items: List<String>): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .body("""{"data":[${items.joinToString(",")}]}""")
            .build()

    private fun indexedItem(
        index: Int,
        vector: FloatArray,
    ): String = """{"index":$index,"embedding":[${vector.joinToString(",")}]}"""

    private fun <T> Outcome<T>.unwrap(): T = (this as Outcome.Ok<T>).value

    private companion object {
        const val PROFILE_ID = "p-embed"
        const val API_KEY_REF = "secret:model:p-embed"
        const val API_KEY = "test-key"
        const val EMBED_MODEL = "embed-model"
        const val UNKNOWN_DIMENSION = 0
    }
}
