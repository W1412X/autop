/**
 * LLM 层错误码（docs/tech/03 §4.4）。`retryable` 语义见 [com.example.autop.core.common.AppError.retryable]。
 */
package com.example.autop.llm

/** 错误码常量（字符串照抄 docs/tech/03 §4.4）。 */
public object LlmErrorCodes {
    /** 角色未分配模型（retryable=false）。 */
    public const val NOT_CONFIGURED: String = "llm.not_configured"

    /** 端侧 embedding 模型资产缺失（retryable=false，重装或更新应用可修复）。 */
    public const val EMBEDDING_MODEL_MISSING: String = "llm.embedding_model_missing"

    /** 端侧 embedding 模型校验失败（sha256 不符或文件损坏，retryable=false）。 */
    public const val EMBEDDING_MODEL_CORRUPT: String = "llm.embedding_model_corrupt"

    /** 端侧 embedding 推理失败（retryable=true）。 */
    public const val EMBEDDING_FAILED: String = "llm.embedding_failed"

    /** 401 / 403（retryable=false）。 */
    public const val AUTH: String = "llm.auth"

    /** 429 重试耗尽（retryable=true）。 */
    public const val RATE_LIMITED: String = "llm.rate_limited"

    /** 余额不足或额度用完（HTTP 402，或 429/400 且响应说明余额/额度不足；retryable=false，充值前重试无意义）。 */
    public const val QUOTA_EXHAUSTED: String = "llm.quota_exhausted"

    /** 上下文超长（retryable=false）。 */
    public const val CONTEXT_EXCEEDED: String = "llm.context_exceeded"

    /** 其他 4xx（retryable=false）。 */
    public const val BAD_REQUEST: String = "llm.bad_request"

    /** 5xx 重试耗尽（retryable=true）。 */
    public const val SERVER: String = "llm.server"

    /** IOException 重试耗尽（retryable=true）。 */
    public const val NETWORK: String = "llm.network"

    /** 读超时（retryable=true）。 */
    public const val TIMEOUT: String = "llm.timeout"

    /** 响应无法解析、缺必需字段（retryable=false）。 */
    public const val BAD_RESPONSE: String = "llm.bad_response"

    /** 服务端拒绝（安全拦截等，retryable=false）。 */
    public const val REFUSED: String = "llm.refused"

    /** 消息序列非法（WP6.5，retryable=false）。 */
    public const val INVALID_HISTORY: String = "llm.invalid_history"
}
