/**
 * agent-store 测试替身（docs/tech/02 §5）。
 */
package com.example.autop.agent.store.testing

import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.llm.EmbeddingProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 空日志。 */
public object NoopLogger : Logger {
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

/** 顺序 id。 */
public class SeqIdGenerator : IdGenerator {
    private var next = 0

    override fun newId(): String = "id-${++next}"
}

/** 确定性向量：同一文本恒得同一向量，不同文本大概率不同。 */
public class FakeEmbeddingProvider(
    override val modelId: String = "test-model",
    override val dimension: Int = 4,
) : EmbeddingProvider {
    override suspend fun embed(texts: List<String>): Outcome<List<FloatArray>> =
        Outcome.Ok(
            texts.map { text ->
                FloatArray(dimension) { i -> ((text.hashCode() * 31 + i * 17).mod(1000)) / 1000f }
            },
        )
}

/** 可切换的 embedding 网关；provider 为 null 模拟未配置。 */
public class FakeEmbeddingGateway(
    provider: EmbeddingProvider? = FakeEmbeddingProvider(),
) : EmbeddingGateway {
    private val state = MutableStateFlow(provider)

    override fun current(): EmbeddingProvider? = state.value

    override val changes: Flow<EmbeddingProvider?> = state

    /** 切换 provider（模拟配置变化）。 */
    public fun set(provider: EmbeddingProvider?) {
        state.value = provider
    }
}
