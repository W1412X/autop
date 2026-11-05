/**
 * memory-impl 单测替身（docs/tech/02 §5）：embedding 网关/提供者与空日志。
 */
package com.example.autop.memory.impl.testing

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.llm.Block
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelRole
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.memory.impl.MemoryRootSeed
import com.example.autop.memory.impl.MemorySeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** 四个内置根目录的固定文案种子（不依赖 Android 资源）。 */
public object TestSeed : MemorySeed {
    override fun roots(): List<MemoryRootSeed> =
        listOf(
            MemoryRootSeed("/profile", "个人画像", "用户的长期画像"),
            MemoryRootSeed("/people", "人物", "用户认识的人"),
            MemoryRootSeed("/apps", "应用", "应用的使用手册"),
            MemoryRootSeed("/tasks", "任务", "进行中的任务"),
        )
}

/** 确定性向量提供者：文本 → 简单哈希向量，记录每次调用的批。 */
public class FakeEmbeddingProvider(
    override val modelId: String = "fake-embedding",
    override val dimension: Int = 2,
) : EmbeddingProvider {
    /** 每次 embed 收到的文本批（用于断言增量跳过）。 */
    public val calls: MutableList<List<String>> = mutableListOf()

    /** 置为 true 后 embed 返回失败（测试重试放弃路径）。 */
    public var failing: Boolean = false

    override suspend fun embed(texts: List<String>): Outcome<List<FloatArray>> {
        calls += texts
        if (failing) return Outcome.Err(AppError(code = "llm.embed_failed", message = "fake failure"))
        return Outcome.Ok(texts.map { vectorOf(it) })
    }

    /** 同一文本永远得到同一向量，不同文本大概率不同。 */
    public fun vectorOf(text: String): FloatArray {
        var h = 17
        text.forEach { h = h * 31 + it.code }
        return floatArrayOf((h % 97).toFloat(), (h % 53).toFloat())
    }
}

/** 可切换提供者的 embedding 网关替身。 */
public class FakeEmbeddingGateway(
    initial: EmbeddingProvider? = null,
) : EmbeddingGateway {
    private val state = MutableStateFlow(initial)

    override fun current(): EmbeddingProvider? = state.value

    override val changes: Flow<EmbeddingProvider?> = state

    /** 切换当前提供者（null 模拟 `llm.embedding.provider = none`）。 */
    public fun switch(provider: EmbeddingProvider?) {
        state.value = provider
    }
}

/** 丢弃全部日志的 Logger。 */
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

/** 固定文本的 LLM 提供者：每次补全返回同一条文本。 */
public class FakeLlmProvider(
    private val text: String,
) : LlmProvider {
    override val profile: ModelProfile
        get() = error("not used")

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            emit(
                LlmStreamEvent.Completed(
                    message = Msg(Role.ASSISTANT, listOf(Block.Text(text))),
                    stopReason = StopReason.END_TURN,
                    usage = null,
                ),
            )
        }
}

/** 固定回复的 LLM 网关：所有角色都返回同一文本的 [FakeLlmProvider]。 */
public class FakeLlmGateway(
    private val text: String,
) : LlmGateway {
    override fun forRole(role: ModelRole): Outcome<LlmProvider> = Outcome.Ok(FakeLlmProvider(text))

    override fun forProfile(profileId: String): Outcome<LlmProvider> = Outcome.Ok(FakeLlmProvider(text))
}
