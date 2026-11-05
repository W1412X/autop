package com.example.autop.core.common

/**
 * 统一错误模型（docs/tech/02 §1.1）。
 *
 * @property code 错误码，必须来自某个 `*ErrorCodes` 常量（代码规范 §2）
 * @property message 英文描述，面向开发者（不进 UI 文案、不进日志外泄敏感内容）
 * @property retryable 调用方是否可以原样重试
 * @property hint 给模型的修正建议（工具错误时使用，代码规范 §7）
 * @property details 结构化上下文（值一律是脱敏后的字符串）
 * @property cause 原始异常（仅进程内使用，不参与序列化）
 */
public data class AppError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val hint: String? = null,
    val details: Map<String, String> = emptyMap(),
    val cause: Throwable? = null,
)

/** 公共错误码（docs/tech/02 §1.1）。各领域错误码定义在所属模块的 `*ErrorCodes` 对象中。 */
public object CommonErrorCodes {
    public const val UNKNOWN: String = "common.unknown"
    public const val CANCELLED: String = "common.cancelled"
    public const val TIMEOUT: String = "common.timeout"
    public const val INVALID_ARGUMENT: String = "common.invalid_argument"
    public const val NOT_FOUND: String = "common.not_found"
    public const val IO: String = "common.io"
    public const val SERIALIZATION: String = "common.serialization"
}
