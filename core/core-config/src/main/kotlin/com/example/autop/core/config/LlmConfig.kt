/**
 * `llm` 领域的配置段与键（docs/tech/09 §1.2）。
 * 段默认值与 `core-config-impl/assets/config/llm.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `llm.http`：HTTP 客户端超时（流式时 readTimeout 为块间隔）。 */
@Serializable
public data class LlmHttpSection(
    @SerialName("connectTimeoutMs") val connectTimeoutMs: Long = 15000,
    @SerialName("readTimeoutMs") val readTimeoutMs: Long = 60000,
    @SerialName("writeTimeoutMs") val writeTimeoutMs: Long = 30000,
) : ConfigSection

/** `llm.retry`：重试策略。 */
@Serializable
public data class LlmRetrySection(
    @SerialName("maxAttempts") val maxAttempts: Int = 3,
    @SerialName("baseDelayMs") val baseDelayMs: Long = 800,
    @SerialName("maxDelayMs") val maxDelayMs: Long = 8000,
    @SerialName("retryStatus") val retryStatus: List<Int> = DEFAULT_RETRY_STATUS,
) : ConfigSection {
    public companion object {
        public val DEFAULT_RETRY_STATUS: List<Int> = listOf(408, 409, 429, 500, 502, 503, 504, 529)
    }
}

/** `llm.errors`：错误识别（正则模式，`|` 分隔；WP6.2）。 */
@Serializable
public data class LlmErrorsSection(
    @SerialName("contextExceededPatterns")
    val contextExceededPatterns: String =
        "context_length_exceeded|maximum context length|prompt is too long|too many tokens|" +
            "exceeds the context window|input is too long|context window|reduce the length",
    /** 上下文超长识别适用的 HTTP 状态码。 */
    @SerialName("contextExceededStatus") val contextExceededStatus: List<Int> = DEFAULT_CONTEXT_EXCEEDED_STATUS,
) : ConfigSection

private const val STATUS_BAD_REQUEST: Int = 400
private const val STATUS_PAYLOAD_TOO_LARGE: Int = 413
private const val STATUS_UNPROCESSABLE: Int = 422

private val DEFAULT_CONTEXT_EXCEEDED_STATUS: List<Int> =
    listOf(STATUS_BAD_REQUEST, STATUS_PAYLOAD_TOO_LARGE, STATUS_UNPROCESSABLE)

/** `llm.anthropic`：Anthropic 协议参数。 */
@Serializable
public data class LlmAnthropicSection(
    @SerialName("apiVersion") val apiVersion: String = "2023-06-01",
) : ConfigSection

/** `llm.image`：发送前图片缩放。 */
@Serializable
public data class LlmImageSection(
    @SerialName("maxEdge") val maxEdge: Int = 1568,
) : ConfigSection

/** `llm.probe`：测试连接。 */
@Serializable
public data class LlmProbeSection(
    @SerialName("timeoutMs") val timeoutMs: Long = 30000,
) : ConfigSection

/**
 * `llm.embedding`：embedding 提供方（记忆检索与历史检索共用）。
 * provider=`openai` 时复用 `profileId` 指向的模型配置。
 */
@Serializable
public data class LlmEmbeddingSection(
    @SerialName("provider") val provider: String = "local",
    @SerialName("profileId") val profileId: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("dimension") val dimension: Int? = null,
    @SerialName("batchSize") val batchSize: Int = 16,
    /** 端侧模型的分词长度上限（含 [CLS]/[SEP]），超出部分截断。 */
    @SerialName("maxSeqLen") val maxSeqLen: Int = 64,
) : ConfigSection

/** `llm` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object LlmConfig {
    public val HTTP: ConfigKey<LlmHttpSection> =
        ConfigKey("llm.http", LlmHttpSection.serializer()) { LlmHttpSection() }
    public val RETRY: ConfigKey<LlmRetrySection> =
        ConfigKey("llm.retry", LlmRetrySection.serializer()) { LlmRetrySection() }
    public val ERRORS: ConfigKey<LlmErrorsSection> =
        ConfigKey("llm.errors", LlmErrorsSection.serializer()) { LlmErrorsSection() }
    public val ANTHROPIC: ConfigKey<LlmAnthropicSection> =
        ConfigKey("llm.anthropic", LlmAnthropicSection.serializer()) { LlmAnthropicSection() }
    public val IMAGE: ConfigKey<LlmImageSection> =
        ConfigKey("llm.image", LlmImageSection.serializer()) { LlmImageSection() }
    public val PROBE: ConfigKey<LlmProbeSection> =
        ConfigKey("llm.probe", LlmProbeSection.serializer()) { LlmProbeSection() }
    public val EMBEDDING: ConfigKey<LlmEmbeddingSection> =
        ConfigKey("llm.embedding", LlmEmbeddingSection.serializer()) { LlmEmbeddingSection() }

    public val ALL: List<ConfigKey<*>> = listOf(HTTP, RETRY, ERRORS, ANTHROPIC, IMAGE, PROBE, EMBEDDING)
}
