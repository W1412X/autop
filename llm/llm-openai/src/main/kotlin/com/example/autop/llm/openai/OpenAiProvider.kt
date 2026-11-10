/**
 * OpenAI 兼容 Provider（docs/tech/03 §5.1）：组装请求 → POST → SSE 流 → 统一事件。
 * 出错抛 [LlmException]；取消收集即经 [okhttp3.Call.cancel] 即刻关闭连接（用例 9）。
 */
package com.example.autop.llm.openai

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.MsgValidator
import com.example.autop.llm.http.AttemptOutcome
import com.example.autop.llm.http.HttpConfig
import com.example.autop.llm.http.RetryConfig
import com.example.autop.llm.http.RetryRunner
import com.example.autop.llm.http.await
import com.example.autop.llm.http.mapHttpError
import com.example.autop.llm.http.parseRetryAfterMs
import com.example.autop.llm.http.sseEvents
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 适配器运行时依赖打包（DI 装配见 wp3-6）。
 * [HttpConfig] 经 [HttpClientFactory][com.example.autop.llm.http.HttpClientFactory] 建 [client]。
 */
@Suppress("LongParameterList") // 传输、鉴权、映射、quirks 与线程/时间/日志注入同属一个装配单元，拆分无收益
public class OpenAiContext(
    public val client: OkHttpClient,
    public val retryConfig: RetryConfig,
    public val secrets: SecretStore,
    public val mapper: OpenAiRequestMapper,
    public val quirksDefaults: OpenAiQuirksDefaults,
    public val dispatchers: DispatcherProvider,
    public val clock: Clock,
    public val logger: Logger,
)

public class OpenAiProvider(
    override val profile: ModelProfile,
    private val context: OpenAiContext,
) : LlmProvider {
    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            val problems = MsgValidator.problems(request.messages)
            if (problems.isNotEmpty()) {
                throw LlmException(
                    AppError(
                        code = LlmErrorCodes.INVALID_HISTORY,
                        message = "invalid message sequence: ${problems.joinToString("; ")}",
                    ),
                )
            }
            val quirks = OpenAiQuirks.of(profile, context.quirksDefaults)
            val body =
                context.mapper
                    .mapChatRequest(request, quirks, profile.model, profile.capabilities.streamUsage)
                    .toString()
            val handle = openStream(body, quirks)
            parseStream(handle, quirks).collect { emit(it) }
        }

    private suspend fun openStream(
        body: String,
        quirks: OpenAiQuirks,
    ): StreamHandle {
        val outcome = RetryRunner(context.retryConfig).run { attemptRequest(body, quirks) }
        return when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw LlmException(outcome.error)
        }
    }

    private suspend fun attemptRequest(
        body: String,
        quirks: OpenAiQuirks,
    ): AttemptOutcome<StreamHandle> {
        val apiKey = context.secrets.get(profile.apiKeyRef)
        if (apiKey == null) {
            return AttemptOutcome.Failure(
                AppError(code = LlmErrorCodes.AUTH, message = "missing api key ref '${profile.apiKeyRef}'"),
                retryable = false,
            )
        }
        val httpRequest =
            Request
                .Builder()
                .url(profile.baseUrl.trimEnd('/') + quirks.chatPath)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .apply { profile.extraHeaders.forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val call = context.client.newCall(httpRequest)
        val response = call.await()
        return if (response.isSuccessful) {
            AttemptOutcome.Success(StreamHandle(call, response))
        } else {
            val snippet = response.body?.string().orEmpty()
            response.close()
            val error =
                mapHttpError(
                    status = response.code,
                    bodySnippet = snippet,
                    contextPatterns = quirks.contextExceededPatterns,
                    contextStatus = quirks.contextExceededStatus.toSet(),
                    retryStatus = context.retryConfig.retryStatus,
                )
            val retryAfterMs = parseRetryAfterMs(response.header("Retry-After"), context.clock.nowMillis())
            AttemptOutcome.Failure(
                error = error,
                retryable = error.retryable,
                retryAfterMs = retryAfterMs,
            )
        }
    }

    /**
     * SSE 流 → 统一事件：阻塞读放在 IO 线程，取消收集经 [awaitClose] 调 [okhttp3.Call.cancel]
     * 立即关连接（`response.close()` 会走 discard 读，不可与阻塞读并发；
     * docs/tech/03 §4.3 流式已发增量后不重试）。读到 EOF 后改用 [Response.close] 归还连接复用（WP6.3）。
     */
    private fun parseStream(
        handle: StreamHandle,
        quirks: OpenAiQuirks,
    ): Flow<LlmStreamEvent> =
        channelFlow {
            val source = handle.response.body?.source()
            if (source == null) {
                close(LlmException(AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "empty response body")))
                return@channelFlow
            }
            val completedNormally = AtomicBoolean(false)
            val parser = OpenAiStreamParser(quirks.reasoningField, quirks.contextExceededPatterns, context.logger)
            launch(context.dispatchers.io) { this@channelFlow.consume(source, parser, completedNormally) }
            awaitClose {
                if (completedNormally.get()) handle.response.close() else handle.call.cancel()
            }
        }

    private suspend fun ProducerScope<LlmStreamEvent>.consume(
        source: BufferedSource,
        parser: OpenAiStreamParser,
        completedNormally: AtomicBoolean,
    ) {
        try {
            var done = false
            source.sseEvents().collect { sse ->
                if (done) return@collect
                if (sse.data == DONE_MARKER) {
                    done = true
                    return@collect
                }
                parser.feed(parseChunk(sse.data)).forEach { send(it) }
            }
            if (!done && !parser.sawFinishReason) {
                throw LlmException(
                    AppError(
                        code = LlmErrorCodes.NETWORK,
                        message = "stream ended before completion",
                        retryable = true,
                    ),
                )
            }
            send(parser.finish())
            completedNormally.set(true)
            close()
        } catch (e: SocketTimeoutException) {
            close(
                LlmException(
                    AppError(code = LlmErrorCodes.TIMEOUT, message = "read timed out", retryable = true, cause = e),
                ),
            )
        } catch (e: IOException) {
            close(
                LlmException(
                    AppError(
                        code = LlmErrorCodes.NETWORK,
                        message = e.message ?: e.toString(),
                        retryable = true,
                        cause = e,
                    ),
                ),
            )
        } catch (e: LlmException) {
            close(e)
        }
    }

    private fun parseChunk(data: String): JsonObject =
        runCatching { Json.parseToJsonElement(data).jsonObject }.getOrElse {
            throw LlmException(AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "unparseable stream chunk"))
        }
}

private const val DONE_MARKER = "[DONE]"

/** 一次成功尝试的句柄：取消经 [call]（线程安全）；[Response.close] 的 discard 读不可与阻塞读并发。 */
private class StreamHandle(
    val call: Call,
    val response: Response,
)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
