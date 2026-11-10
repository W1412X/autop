/**
 * 采样阶段（docs/tech/04 §3.2/§3.8）：请求组装、JSON 回退解析、
 * 流式采样与 `llm.context_exceeded` 强制压缩重试；从 TurnLoop 拆出。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunState
import com.example.autop.agent.protocol.FallbackMode
import com.example.autop.agent.protocol.RequestBuilder
import com.example.autop.agent.protocol.ResolvedCalls
import com.example.autop.agent.protocol.sanitizeTextsOf
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.Msg
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolSchema
import kotlinx.coroutines.delay

private const val TAG = "TurnSampler"

/** 一次采样的走向。 */
internal sealed interface Sampled {
    data class Ok(
        val completed: LlmStreamEvent.Completed,
    ) : Sampled

    /** 采样失败或压缩硬失败：Run 以 FAILED 结束（[cause] 供日志带堆栈）。 */
    data class Failed(
        val error: AppError,
        val cause: Throwable? = null,
    ) : Sampled
}

/** 采样器（每次 Run 一个）。 */
internal class TurnSampler(
    private val req: RunRequest,
    private val controller: RunController,
    private val prompts: PromptRepository,
    private val logger: Logger,
) {
    private var turn = 0
    private lateinit var fallback: FallbackMode
    private lateinit var requestBuilder: RequestBuilder

    /** 绑定模型能力（docs/tech/04 §3.2/§3.5/§3.8）。 */
    fun bindProfile(provider: LlmProvider) {
        val profile = provider.profile
        fallback =
            FallbackMode(
                prompts = prompts,
                nativeTools = profile.capabilities.tools,
                parseRetriesLimit = req.config[AgentConfig.FALLBACK].parseRetries,
            )
        requestBuilder =
            RequestBuilder(
                prompts = prompts,
                profile = profile,
                thinking = req.config[AgentConfig.THINKING],
                texts = sanitizeTextsOf(prompts),
            )
    }

    /** 非原生 tools 时的静态工具清单（组装进 system，docs/tech/04 §3.8）。 */
    fun staticToolList(schemas: List<ToolSchema>): String? = fallback.staticToolList(schemas)

    /** 解析本轮工具调用（原生 ToolCall 或 JSON 回退）。 */
    fun resolveCalls(message: Msg): ResolvedCalls = fallback.resolveCalls(message, turn)

    /**
     * 采样一次；`llm.context_exceeded` 时强制压缩后重试本轮（每轮最多 1 次，docs/tech/04 §3.2）。
     * [system] 取最新 system（压缩后可能已重新组装）；[compact] 是 TurnLoop 的唯一压缩入口（WP4.1）。
     */
    suspend fun sample(
        provider: LlmProvider,
        schemas: List<ToolSchema>,
        finalRound: Boolean,
        system: () -> List<SystemPart>,
        render: suspend () -> List<Msg>,
        compact: suspend () -> CompactResult,
    ): Sampled {
        turn++
        val build: suspend () -> LlmRequest = { requestBuilder.build(system(), render(), schemas, finalRound) }
        val first = streamWithRetry(provider, build)
        if (first !is Sampled.Failed || first.error.code != LlmErrorCodes.CONTEXT_EXCEEDED) return first
        return when (val compacted = compact()) {
            is CompactResult.End -> Sampled.Failed(compacted.error)
            CompactResult.Continue -> streamWithRetry(provider, build)
        }
    }

    /** 流式中途可重试错误的回合级重试（WP6.6）：指数退避，超限返回最后一次失败。 */
    private suspend fun streamWithRetry(
        provider: LlmProvider,
        build: suspend () -> LlmRequest,
    ): Sampled {
        val cfg = req.config[AgentConfig.SAMPLING]
        var attempt = 0
        while (true) {
            val result = stream(provider, build())
            val failed = result as? Sampled.Failed ?: return result
            val retry =
                failed.error.retryable &&
                    failed.error.code != LlmErrorCodes.CONTEXT_EXCEEDED &&
                    attempt < cfg.maxStreamRetries
            if (!retry) return result
            attempt += 1
            controller.emit(AgentEvent.SampleRetrying(attempt, failed.error.code))
            logger.w(TAG, "sample_retry", "runId" to req.runId, "attempt" to attempt, "code" to failed.error.code)
            delay(minOf(cfg.retryBaseDelayMs * (1L shl (attempt - 1)), cfg.retryMaxDelayMs))
        }
    }

    /** 收集一次流式采样，转发增量事件。 */
    private suspend fun stream(
        provider: LlmProvider,
        request: LlmRequest,
    ): Sampled {
        controller.setState(RunState.SAMPLING)
        var last: LlmStreamEvent.Completed? = null
        try {
            provider.stream(request).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> controller.emit(AgentEvent.TextDelta(event.text))
                    is LlmStreamEvent.ThinkingDelta -> controller.emit(AgentEvent.ThinkingDelta(event.text))
                    is LlmStreamEvent.ToolCallStarted ->
                        controller.emit(AgentEvent.ToolCallStreaming(event.index, event.name, event.id))
                    is LlmStreamEvent.Completed -> last = event
                    else -> Unit
                }
            }
        } catch (e: LlmException) {
            return Sampled.Failed(e.error, e)
        }
        val completed = last
        return if (completed != null) {
            Sampled.Ok(completed)
        } else {
            Sampled.Failed(AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "stream ended without Completed"))
        }
    }
}
