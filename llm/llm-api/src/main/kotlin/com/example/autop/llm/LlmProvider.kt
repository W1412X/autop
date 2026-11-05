/**
 * Provider 端口与网关（docs/tech/03 §2）：适配器实现 [LlmProviderFactory]（扩展点，
 * 按 `protocolId` 匹配，禁止按 baseUrl 分支），runtime 经 [LlmGateway] 取 provider。
 */
package com.example.autop.llm

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/** 模型角色（docs/tech/03 §2）：MAIN 主对话 / VISION 视觉 / LIGHT 轻量。 */
public enum class ModelRole {
    MAIN,
    VISION,
    LIGHT,
}

/** 一个模型配置对应的流式提供者。 */
public interface LlmProvider {
    public val profile: ModelProfile

    /**
     * 冷流；取消收集即取消底层 HTTP 请求。
     * 出错时以 [LlmException] 结束（携 [AppError]）。
     */
    public fun stream(request: LlmRequest): Flow<LlmStreamEvent>
}

/** [LlmProvider] 工厂（扩展点：DI 多绑定，按 [protocolId] 匹配 `profile.protocol`）。 */
public interface LlmProviderFactory {
    /** 协议标识（`"openai"` / `"anthropic"`）。 */
    public val protocolId: String

    public fun create(profile: ModelProfile): LlmProvider
}

/** 模型网关入口（runtime 与其他模块使用）。 */
public interface LlmGateway {
    /** 取 [role] 对应的 provider；未配置返回 `Err(llm.not_configured)`。 */
    public fun forRole(role: ModelRole): Outcome<LlmProvider>

    /** 按配置 id 取 provider；不存在返回 `Err(llm.not_configured)`。 */
    public fun forProfile(profileId: String): Outcome<LlmProvider>
}

/** 流式出错的异常载体（业务失败仍以 [error] 的错误码表达）。 */
public class LlmException(
    public val error: AppError,
) : RuntimeException(error.message)

/**
 * 便捷扩展：收集流取最后一个 [LlmStreamEvent.Completed]（docs/tech/03 §2）；
 * [LlmException] 转 [Outcome.Err]，取消原样传播。
 */
public suspend fun LlmProvider.complete(request: LlmRequest): Outcome<LlmStreamEvent.Completed> =
    try {
        var last: LlmStreamEvent.Completed? = null
        stream(request).collect { event -> if (event is LlmStreamEvent.Completed) last = event }
        val completed = last
        if (completed != null) {
            Outcome.Ok(completed)
        } else {
            Outcome.Err(
                AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "llm stream ended without Completed event"),
            )
        }
    } catch (e: LlmException) {
        Outcome.Err(e.error)
    }
