/**
 * Anthropic 兼容 Provider（docs/tech/03 §5.2）：组装请求 → POST → SSE 事件 → 统一事件。
 * 出错抛 [LlmException]；取消收集即经 [okhttp3.Call.cancel] 即刻关闭连接（用例 9）。
 */
package com.example.autop.llm.anthropic

import com.example.autop.core.common.AppError
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
import com.example.autop.llm.http.RetryConfig
import com.example.autop.llm.http.RetryRunner
import com.example.autop.llm.http.await
import com.example.autop.llm.http.mapHttpError
import com.example.autop.llm.http.parseRetryAfterMs
import com.example.autop.llm.http.sseEvents
import kotlinx.coroutines.Dispatchers
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

/** 适配器运行时依赖打包（DI 装配见 wp3-6）。 */
public class AnthropicContext(
    public val client: OkHttpClient,
    public val retryConfig: RetryConfig,
    public val secrets: SecretStore,
    public val mapper: AnthropicRequestMapper,
    public val quirksDefaults: AnthropicQuirksDefaults,
)

public class AnthropicProvider(
    override val profile: ModelProfile,
    private val context: AnthropicContext,
) : LlmProvider {
    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            MsgValidator.validate(request.messages)
            val quirks = AnthropicQuirks.of(profile, context.quirksDefaults)
            val body = context.mapper.mapChatRequest(request, quirks, profile.model).toString()
            val handle = openStream(body, quirks)
            parseStream(handle, quirks).collect { emit(it) }
        }

    private suspend fun openStream(
        body: String,
        quirks: AnthropicQuirks,
    ): StreamHandle {
        val outcome = RetryRunner(context.retryConfig).run { attemptRequest(body, quirks) }
        return when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw LlmException(outcome.error)
        }
    }

    private suspend fun attemptRequest(
        body: String,
        quirks: AnthropicQuirks,
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
                .url(profile.baseUrl.trimEnd('/') + quirks.messagesPath)
                .header("x-api-key", apiKey)
                .header("anthropic-version", context.quirksDefaults.apiVersion)
                .header("content-type", "application/json")
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
            val error = mapHttpError(response.code, snippet, quirks.contextExceededPatterns)
            val retryAfterMs = parseRetryAfterMs(response.header("Retry-After"), System.currentTimeMillis())
            AttemptOutcome.Failure(
                error = error,
                retryable = response.code in context.retryConfig.retryStatus,
                retryAfterMs = retryAfterMs,
            )
        }
    }

    /**
     * SSE 流 → 统一事件：阻塞读放在 IO 线程，取消收集经 [awaitClose] 调 [okhttp3.Call.cancel]
     * 立即关连接（`response.close()` 会走 discard 读，不可与阻塞读并发；
     * docs/tech/03 §4.3 流式已发增量后不重试）。
     */
    private fun parseStream(
        handle: StreamHandle,
        quirks: AnthropicQuirks,
    ): Flow<LlmStreamEvent> =
        channelFlow {
            val source = handle.response.body?.source()
            if (source == null) {
                close(LlmException(AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "empty response body")))
                return@channelFlow
            }
            val parser = AnthropicStreamParser(quirks.contextExceededPatterns)
            launch(Dispatchers.IO) { this@channelFlow.consume(source, parser) }
            awaitClose { handle.call.cancel() }
        }

    private suspend fun ProducerScope<LlmStreamEvent>.consume(
        source: BufferedSource,
        parser: AnthropicStreamParser,
    ) {
        try {
            source.sseEvents().collect { sse ->
                parser.feed(sse.event, parseChunk(sse.data)).forEach { send(it) }
            }
            parser.finishIfNeeded().forEach { send(it) }
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

/** 一次成功尝试的句柄：取消经 [call]（线程安全）；[Response.close] 的 discard 读不可与阻塞读并发。 */
private class StreamHandle(
    val call: Call,
    val response: Response,
)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
