package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.llm.Block
import com.example.autop.llm.EmbeddingConfig
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.llm.EmbeddingProviderFactory
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmProviderFactory
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 按脚本响应的假 Provider（记录收到的请求，[handler] 返回 null 时不发事件）。 */
internal class FakeLlmProvider(
    override val profile: ModelProfile,
    private val handler: suspend (LlmRequest) -> LlmStreamEvent.Completed? = { completedEvent() },
) : LlmProvider {
    val requests: MutableList<LlmRequest> = mutableListOf()

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            requests += request
            val result = handler(request)
            if (result != null) {
                emit(result)
            }
        }
}

/** 静默 Logger（测试不需要日志输出）。 */
internal object NoopLogger : Logger {
    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit
}

/** 记录创建行为的假 [LlmProviderFactory]。 */
internal class FakeLlmProviderFactory(
    override val protocolId: String,
    private val onCreate: (ModelProfile) -> LlmProvider = { FakeLlmProvider(it) },
) : LlmProviderFactory {
    val created: MutableList<LlmProvider> = mutableListOf()

    override fun create(profile: ModelProfile): LlmProvider = onCreate(profile).also { created += it }
}

/** 固定向量的假 [EmbeddingProvider]。 */
internal class FakeEmbeddingProvider(
    override val modelId: String = "fake:default",
    override val dimension: Int = 4,
) : EmbeddingProvider {
    override suspend fun embed(texts: List<String>): Outcome<List<FloatArray>> =
        Outcome.Ok(texts.map { FloatArray(dimension) })
}

/** 记录配置透传的假 [EmbeddingProviderFactory]。 */
internal class FakeEmbeddingFactory(
    override val id: String = "fake",
) : EmbeddingProviderFactory {
    val configs: MutableList<EmbeddingConfig> = mutableListOf()

    override fun create(config: EmbeddingConfig): EmbeddingProvider {
        configs += config
        return FakeEmbeddingProvider(modelId = "$id:${config.model ?: "default"}")
    }
}

/** 一次成功补全的 [LlmStreamEvent.Completed]。 */
internal fun completedEvent(): LlmStreamEvent.Completed =
    LlmStreamEvent.Completed(
        message = Msg(role = Role.USER, blocks = emptyList()),
        stopReason = StopReason.END_TURN,
        usage = null,
    )

/** 一次调用 echo 工具的 [LlmStreamEvent.Completed]（工具调用探测判定用）。 */
internal fun echoToolCompletedEvent(): LlmStreamEvent.Completed =
    LlmStreamEvent.Completed(
        message = Msg(role = Role.ASSISTANT, blocks = listOf(Block.ToolCall("call_probe", "echo", "{}"))),
        stopReason = StopReason.TOOL_USE,
        usage = null,
    )

/** 取出成功值（测试断言辅助）。 */
internal fun <T> Outcome<T>.unwrap(): T = (this as Outcome.Ok<T>).value

/** 取出错误（测试断言辅助）。 */
internal fun <T> Outcome<T>.unwrapError(): AppError = (this as Outcome.Err).error
