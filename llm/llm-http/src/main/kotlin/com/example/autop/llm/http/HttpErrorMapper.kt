/**
 * HTTP 失败到统一错误码的映射（docs/tech/03 §4.3–§4.4、agent-harness-overhaul WP6.1）。
 * `retryable` 的单一来源：恒等于 `status in retryStatus`（`CONTEXT_EXCEEDED` 除外，恒 false）。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.llm.LlmErrorCodes
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 状态码映射（docs/tech/03 §4.4）；[contextPatterns] 为 `|` 拆分后的子串列表，
 * [contextStatus] 触发上下文超长识别，[retryStatus] 决定可重试性。
 */
public fun mapHttpError(
    status: Int,
    bodySnippet: String,
    contextPatterns: List<String>,
    contextStatus: Set<Int>,
    retryStatus: Set<Int>,
): AppError {
    val code =
        when {
            status in contextStatus && matchesContextExceeded(bodySnippet, contextPatterns) ->
                LlmErrorCodes.CONTEXT_EXCEEDED
            status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> LlmErrorCodes.AUTH
            status == HTTP_TOO_MANY_REQUESTS -> LlmErrorCodes.RATE_LIMITED
            status in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> LlmErrorCodes.SERVER
            status in HTTP_BAD_REQUEST..HTTP_CLIENT_ERROR_MAX -> LlmErrorCodes.BAD_REQUEST
            else -> LlmErrorCodes.BAD_RESPONSE
        }
    val retryable = code != LlmErrorCodes.CONTEXT_EXCEEDED && status in retryStatus
    return error(code, status, bodySnippet, retryable)
}

/** 上下文超长识别：body 含任一子串（大小写不敏感，docs/tech/03 §4.3）。 */
public fun matchesContextExceeded(
    bodySnippet: String,
    contextPatterns: List<String>,
): Boolean {
    val lower = bodySnippet.lowercase()
    return contextPatterns.any { pattern -> pattern.isNotBlank() && lower.contains(pattern.lowercase()) }
}

/**
 * 解析 `Retry-After` 头（秒数或 HTTP 日期，docs/tech/03 §4.3）；
 * 无法解析返回 null。[nowMs] 用于日期换算（测试注入）。
 */
public fun parseRetryAfterMs(
    value: String?,
    nowMs: Long,
): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return secondsToMs(text) ?: dateDeltaMs(text, nowMs)
}

private fun secondsToMs(text: String): Long? = text.toLongOrNull()?.let { (it * MS_PER_SECOND).coerceAtLeast(0) }

private fun dateDeltaMs(
    text: String,
    nowMs: Long,
): Long? {
    val dateMs =
        runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
    return (dateMs - nowMs).coerceAtLeast(0)
}

private fun error(
    code: String,
    status: Int,
    bodySnippet: String,
    retryable: Boolean = false,
): AppError =
    AppError(
        code = code,
        message = "http $status: ${bodySnippet.take(BODY_SNIPPET_LIMIT)}",
        retryable = retryable,
    )

private const val MS_PER_SECOND = 1000L
private const val BODY_SNIPPET_LIMIT = 200
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_MIN = 500
private const val HTTP_SERVER_ERROR_MAX = 599
private const val HTTP_CLIENT_ERROR_MAX = 499
