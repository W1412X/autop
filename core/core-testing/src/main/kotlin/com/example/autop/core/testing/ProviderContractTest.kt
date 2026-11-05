/**
 * Provider 契约测试基类（docs/tech/03 §5.3）：9 个用例对每个适配器逐一执行；
 * 线格式夹具由子类从 `src/test/resources/fixtures/` 提供（文件名与用例编号对应）。
 */
package com.example.autop.core.testing

import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.StopReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 被测 Provider 的装配端口：子类实现本接口并把自身作为 [ProviderContractTest.target]。 */
public interface ContractTarget {
    /** 各用例的线格式夹具（经 [Fixtures] 加载）。 */
    public val fixtures: ContractFixtures

    /**
     * 构造被测 Provider。[baseUrl] 指向测试服务器；重试配置要求 `maxAttempts >= 2`
     * 且 `retryStatus` 含 429（用例 6 要求毫秒级退避后立即重试）。
     */
    public fun createProvider(baseUrl: String): LlmProvider

    /** 断言用例 4 的请求体：工具结果里的图片按各自线格式编码（base64 data URL / source 块）。 */
    public fun assertImageRequest(body: String)
}

public abstract class ProviderContractTest {
    /** 被测适配器的装配（子类实现 [ContractTarget] 并回传自身）。 */
    protected abstract val target: ContractTarget

    @Test
    public fun textStreamEmitsDeltasAndCompletes() {
        withMockServer { server ->
            server.enqueue(sseResponse(target.fixtures.textStream))
            val events = collectEvents(target.createProvider(server.url("/").toString()), contractTextRequest())

            val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertThat(text).isEqualTo(target.fixtures.textFinal)
            val completed = events.last() as LlmStreamEvent.Completed
            assertThat(completed.stopReason).isEqualTo(StopReason.END_TURN)
            val blocks = completed.message.blocks.filterIsInstance<Block.Text>()
            assertThat(blocks.joinToString("") { it.text }).isEqualTo(target.fixtures.textFinal)
        }
    }

    @Test
    public fun singleToolCallArgumentsArriveInFragments() {
        withMockServer { server ->
            server.enqueue(sseResponse(target.fixtures.singleToolStream))
            val events = collectEvents(target.createProvider(server.url("/").toString()), contractTextRequest())

            val started = events.filterIsInstance<LlmStreamEvent.ToolCallStarted>()
            assertThat(started).hasSize(1)
            assertThat(started.single().id).isEqualTo(target.fixtures.singleTool.id)
            assertThat(started.single().name).isEqualTo(target.fixtures.singleTool.name)
            val fragments = events.filterIsInstance<LlmStreamEvent.ToolCallArgsDelta>()
            assertThat(fragments.size).isAtLeast(MIN_ARG_FRAGMENTS)
            assertThat(fragments.joinToString("") { it.fragment }).isEqualTo(target.fixtures.singleTool.argsJson)
            val completed = events.last() as LlmStreamEvent.Completed
            assertThat(completed.stopReason).isEqualTo(StopReason.TOOL_USE)
            val call =
                completed.message.blocks
                    .filterIsInstance<Block.ToolCall>()
                    .single()
            assertThat(call.id).isEqualTo(target.fixtures.singleTool.id)
            assertThat(call.argsJson).isEqualTo(target.fixtures.singleTool.argsJson)
        }
    }

    @Test
    public fun multiToolCallsMapToIndexedFragments() {
        withMockServer { server ->
            server.enqueue(sseResponse(target.fixtures.multiToolStream))
            val events = collectEvents(target.createProvider(server.url("/").toString()), contractTextRequest())

            val started = events.filterIsInstance<LlmStreamEvent.ToolCallStarted>()
            assertThat(started.map { it.id })
                .containsExactlyElementsIn(target.fixtures.multiTools.map { it.id })
                .inOrder()
            val argsById =
                events
                    .filterIsInstance<LlmStreamEvent.ToolCallArgsDelta>()
                    .groupBy { delta -> started.single { it.index == delta.index }.id }
                    .mapValues { (_, fragments) -> fragments.joinToString("") { it.fragment } }
            for (tool in target.fixtures.multiTools) {
                assertThat(argsById[tool.id]).isEqualTo(tool.argsJson)
            }
            val completed = events.last() as LlmStreamEvent.Completed
            val calls = completed.message.blocks.filterIsInstance<Block.ToolCall>()
            assertThat(calls.map { it.id })
                .containsExactlyElementsIn(target.fixtures.multiTools.map { it.id })
                .inOrder()
        }
    }

    @Test
    public fun toolResultImageIsEncodedInRequest() {
        withMockServer { server ->
            server.enqueue(sseResponse(target.fixtures.imageStream))
            val request = contractToolRequest(listOf(target.fixtures.singleTool))

            collectEvents(target.createProvider(server.url("/").toString()), request)

            val recorded = requireNotNull(server.takeRequest(TAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            target.assertImageRequest(requireNotNull(recorded.body).utf8())
        }
    }

    @Test
    public fun usageIsReportedAndAttachedToCompleted() {
        withMockServer { server ->
            server.enqueue(sseResponse(target.fixtures.usageStream))
            val events = collectEvents(target.createProvider(server.url("/").toString()), contractTextRequest())

            val reported = events.filterIsInstance<LlmStreamEvent.UsageReported>()
            assertThat(reported.map { it.usage }).contains(target.fixtures.expectedUsage)
            val completed = events.last() as LlmStreamEvent.Completed
            assertThat(completed.usage).isEqualTo(target.fixtures.expectedUsage)
        }
    }

    @Test
    public fun rateLimitedAttemptIsRetried() {
        withMockServer { server ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(HTTP_TOO_MANY_REQUESTS)
                    .addHeader(RETRY_AFTER_HEADER, RETRY_AFTER_ZERO)
                    .body(target.fixtures.rateLimitBody)
                    .build(),
            )
            server.enqueue(sseResponse(target.fixtures.textStream))
            val events = collectEvents(target.createProvider(server.url("/").toString()), contractTextRequest())

            assertThat(server.requestCount).isEqualTo(RETRY_EXPECTED_COUNT)
            val completed = events.last() as LlmStreamEvent.Completed
            assertThat(completed.message.blocks.filterIsInstance<Block.Text>()).isNotEmpty()
        }
    }

    @Test
    public fun truncatedStreamAfterDeltasFailsWithoutRetry() {
        val connections = AtomicInteger()
        val server = startTruncatedServer(target.fixtures.truncatedStream, connections)
        try {
            val result =
                collectEventsResult(
                    target.createProvider("http://127.0.0.1:${server.localPort}"),
                    contractTextRequest(),
                )

            val error = requireNotNull(result.error)
            assertThat(error.error.code).isEqualTo(LlmErrorCodes.NETWORK)
            assertThat(result.events.filterIsInstance<LlmStreamEvent.TextDelta>()).isNotEmpty()
            assertThat(connections.get()).isEqualTo(SINGLE_CONNECTION)
        } finally {
            server.close()
        }
    }

    @Test
    public fun contextExceededResponseMapsToContextExceeded() {
        withMockServer { server ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(HTTP_BAD_REQUEST)
                    .body(target.fixtures.contextExceededBody)
                    .build(),
            )
            val result = collectEventsResult(target.createProvider(server.url("/").toString()), contractTextRequest())

            val error = requireNotNull(result.error)
            assertThat(error.error.code).isEqualTo(LlmErrorCodes.CONTEXT_EXCEEDED)
        }
    }

    @Test
    public fun cancellingCollectionClosesTheConnection() {
        val clientClosed = CountDownLatch(1)
        val server = startHangServer(target.fixtures.hangStream, clientClosed)
        try {
            val first =
                runBlocking {
                    withTimeout(COLLECT_TIMEOUT_MS) {
                        target
                            .createProvider("http://127.0.0.1:${server.localPort}")
                            .stream(contractTextRequest())
                            .first()
                    }
                }

            assertThat(first).isInstanceOf(LlmStreamEvent.TextDelta::class.java)
            assertThat(clientClosed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        } finally {
            server.close()
        }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val TAKE_TIMEOUT_SECONDS = 5L
        const val CLOSE_TIMEOUT_SECONDS = 5L
        const val COLLECT_TIMEOUT_MS = 30_000L
        const val MIN_ARG_FRAGMENTS = 2
        const val RETRY_EXPECTED_COUNT = 2
        const val SINGLE_CONNECTION = 1
        const val RETRY_AFTER_HEADER = "Retry-After"
        const val RETRY_AFTER_ZERO = "0"
    }
}
