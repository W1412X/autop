/**
 * OpenAI 兼容 Provider 的契约测试（docs/tech/03 §5.3）：复用 [ProviderContractTest]
 * 9 用例，夹具取自 `src/test/resources/fixtures/`。
 */
package com.example.autop.llm.openai

import com.example.autop.core.testing.ContractFixtures
import com.example.autop.core.testing.ContractTarget
import com.example.autop.core.testing.ContractTool
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeSecretStore
import com.example.autop.core.testing.Fixtures
import com.example.autop.core.testing.ProviderContractTest
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.example.autop.llm.http.RetryConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

internal class OpenAiContractTest :
    ProviderContractTest(),
    ContractTarget {
    override val target: ContractTarget get() = this

    override val fixtures: ContractFixtures =
        ContractFixtures(
            textStream = fx("01-text-stream.sse"),
            textFinal = "Hello world",
            singleTool = ContractTool("call_1", "echo", """{"x":1}"""),
            singleToolStream = fx("02-single-tool.sse"),
            multiTools =
                listOf(
                    ContractTool("call_1", "sum", """{"a":2}"""),
                    ContractTool("call_2", "sum", """{"b":3}"""),
                ),
            multiToolStream = fx("03-multi-tool.sse"),
            imageStream = fx("04-image-stream.sse"),
            usageStream = fx("05-usage.sse"),
            expectedUsage = Usage(30, 12, cacheReadTokens = 5),
            rateLimitBody = fx("06-rate-limit.json"),
            truncatedStream = fx("07-truncated.sse"),
            contextExceededBody = fx("08-context-exceeded.json"),
            hangStream = fx("09-hang.sse"),
        )

    override fun createProvider(baseUrl: String): LlmProvider =
        OpenAiProvider(
            profile =
                ModelProfile(
                    id = "contract",
                    displayName = "contract",
                    protocol = "openai",
                    baseUrl = baseUrl,
                    apiKeyRef = API_KEY_REF,
                    model = "contract-model",
                    contextWindow = 8192,
                    maxOutputTokens = 256,
                ),
            context =
                OpenAiContext(
                    client = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
                    retryConfig =
                        RetryConfig(
                            maxAttempts = 2,
                            baseDelayMs = 1,
                            maxDelayMs = 5,
                            retryStatus = setOf(408, 429, 500, 502, 503),
                        ),
                    secrets = FakeSecretStore(mapOf(API_KEY_REF to "test-key")),
                    mapper = OpenAiRequestMapper(FakeImageLoader(), FakePrompts()),
                    quirksDefaults =
                        OpenAiQuirksDefaults(
                            contextExceededPatterns = listOf("maximum context length"),
                            imageMaxEdge = 1024,
                        ),
                    dispatchers = TestDispatcherProvider(Dispatchers.IO),
                    clock = FakeClock(),
                    logger = RecordingLogger(),
                ),
        )

    override fun assertImageRequest(body: String) {
        val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        val tool = messages[messages.size - TOOL_MESSAGE_OFFSET].jsonObject
        assertThat(tool["role"]!!.jsonPrimitive.content).isEqualTo("tool")
        assertThat(tool["tool_call_id"]!!.jsonPrimitive.content).isEqualTo(fixtures.singleTool.id)
        val user = messages.last().jsonObject
        assertThat(user["role"]!!.jsonPrimitive.content).isEqualTo("user")
        val image = user["content"]!!.jsonArray.last().jsonObject
        assertThat(image["type"]!!.jsonPrimitive.content).isEqualTo("image_url")
        val url = image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertThat(url).isEqualTo(EXPECTED_DATA_URL)
    }

    /** WP6.3：无 [DONE] 且无 finish_reason 的流在 EOF 处按可重试网络错误收口。 */
    @Test
    fun streamWithoutDoneAndFinishReasonIsNetworkError() {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(sseFixture(fx(FIXTURE_NO_DONE)))
            val error =
                assertThrows(LlmException::class.java) {
                    runBlocking {
                        withTimeout(COLLECT_TIMEOUT_MS) {
                            createProvider(server.url("/").toString()).stream(minimalRequest()).toList()
                        }
                    }
                }
            assertThat(error.error.code).isEqualTo(LlmErrorCodes.NETWORK)
            assertThat(error.error.retryable).isTrue()
        } finally {
            server.close()
        }
    }

    /** WP6.3：finish_reason 之后收到 [DONE] 正常完成，[DONE] 之后的内容丢弃但读到 EOF。 */
    @Test
    fun streamWithDoneAfterFinishCompletes() {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(sseFixture(fx(FIXTURE_DONE_AFTER_FINISH)))
            val events =
                runBlocking {
                    withTimeout(COLLECT_TIMEOUT_MS) {
                        createProvider(server.url("/").toString()).stream(minimalRequest()).toList()
                    }
                }
            val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertThat(text).isEqualTo("Hello")
            val completed = events.last() as LlmStreamEvent.Completed
            assertThat(completed.stopReason).isEqualTo(StopReason.END_TURN)
            assertThat(completed.message.blocks).containsExactly(Block.Text("Hello"))
        } finally {
            server.close()
        }
    }

    private fun sseFixture(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(HTTP_OK)
            .addHeader("content-type", "text/event-stream")
            .body(body)
            .build()

    private fun minimalRequest(): LlmRequest =
        LlmRequest(
            system = emptyList(),
            messages = listOf(Msg(Role.USER, listOf(Block.Text("hi")))),
            maxOutputTokens = MAX_OUTPUT_TOKENS,
        )

    private fun fx(path: String): String = Fixtures.load(path)

    private companion object {
        const val API_KEY_REF = "secret:model:contract"
        const val READ_TIMEOUT_SECONDS = 10L
        const val TOOL_MESSAGE_OFFSET = 2
        const val EXPECTED_DATA_URL = "data:image/png;base64,AQID"
        const val COLLECT_TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT_TOKENS = 128
        const val HTTP_OK = 200
        const val FIXTURE_NO_DONE = "10-no-done.sse"
        const val FIXTURE_DONE_AFTER_FINISH = "11-done-after-finish.sse"
    }
}
