/**
 * Anthropic 兼容 Provider 的契约测试（docs/tech/03 §5.3）：复用 [ProviderContractTest]
 * 9 用例，夹具取自 `src/test/resources/fixtures/`。
 */
package com.example.autop.llm.anthropic

import com.example.autop.core.testing.ContractFixtures
import com.example.autop.core.testing.ContractTarget
import com.example.autop.core.testing.ContractTool
import com.example.autop.core.testing.FakeSecretStore
import com.example.autop.core.testing.Fixtures
import com.example.autop.core.testing.ProviderContractTest
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Usage
import com.example.autop.llm.http.RetryConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal class AnthropicContractTest :
    ProviderContractTest(),
    ContractTarget {
    override val target: ContractTarget get() = this

    override val fixtures: ContractFixtures =
        ContractFixtures(
            textStream = fx("01-text-stream.sse"),
            textFinal = "Hello world",
            singleTool = ContractTool("toolu_01", "echo", """{"x":1}"""),
            singleToolStream = fx("02-single-tool.sse"),
            multiTools =
                listOf(
                    ContractTool("toolu_01", "sum", """{"a":2}"""),
                    ContractTool("toolu_02", "sum", """{"b":3}"""),
                ),
            multiToolStream = fx("03-multi-tool.sse"),
            imageStream = fx("04-image-stream.sse"),
            usageStream = fx("05-usage.sse"),
            expectedUsage = Usage(37, 12, cacheReadTokens = 5, cacheWriteTokens = 2),
            rateLimitBody = fx("06-rate-limit.json"),
            truncatedStream = fx("07-truncated.sse"),
            contextExceededBody = fx("08-context-exceeded.json"),
            hangStream = fx("09-hang.sse"),
        )

    override fun createProvider(baseUrl: String): LlmProvider =
        AnthropicProvider(
            profile =
                ModelProfile(
                    id = "contract",
                    displayName = "contract",
                    protocol = "anthropic",
                    baseUrl = baseUrl,
                    apiKeyRef = API_KEY_REF,
                    model = "contract-model",
                    contextWindow = 8192,
                    maxOutputTokens = 256,
                ),
            context =
                AnthropicContext(
                    client = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
                    retryConfig =
                        RetryConfig(
                            maxAttempts = 2,
                            baseDelayMs = 1,
                            maxDelayMs = 5,
                            retryStatus = setOf(408, 429, 500, 502, 503, 529),
                        ),
                    secrets = FakeSecretStore(mapOf(API_KEY_REF to "test-key")),
                    mapper = AnthropicRequestMapper(FakeImageLoader(), FakePrompts()),
                    quirksDefaults =
                        AnthropicQuirksDefaults(
                            contextExceededPatterns = listOf("prompt is too long"),
                            imageMaxEdge = 1024,
                            apiVersion = "2023-06-01",
                        ),
                ),
        )

    override fun assertImageRequest(body: String) {
        val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        val blocks = messages.last().jsonObject["content"]!!.jsonArray
        val toolResult = blocks.last().jsonObject
        assertThat(toolResult["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
        assertThat(toolResult["tool_use_id"]!!.jsonPrimitive.content).isEqualTo(fixtures.singleTool.id)
        val image = toolResult["content"]!!.jsonArray.last().jsonObject
        assertThat(image["type"]!!.jsonPrimitive.content).isEqualTo("image")
        val source = image["source"]!!.jsonObject
        assertThat(source["type"]!!.jsonPrimitive.content).isEqualTo("base64")
        assertThat(source["media_type"]!!.jsonPrimitive.content).isEqualTo(EXPECTED_MEDIA_TYPE)
        assertThat(source["data"]!!.jsonPrimitive.content).isEqualTo(EXPECTED_BASE64)
    }

    private fun fx(path: String): String = Fixtures.load(path)

    private companion object {
        const val API_KEY_REF = "secret:model:contract"
        const val READ_TIMEOUT_SECONDS = 10L
        const val EXPECTED_MEDIA_TYPE = "image/png"
        const val EXPECTED_BASE64 = "AQID"
    }
}
