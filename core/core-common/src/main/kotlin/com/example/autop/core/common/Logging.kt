package com.example.autop.core.common

import java.security.MessageDigest

/**
 * 结构化日志端口（docs/tech/02 §1.4）。
 *
 * `kv` 是结构化字段；key 以 `text`、`content` 结尾的值由实现负责脱敏（见 `LogRedact`）。
 * 禁止把密钥、密码框内容、未脱敏屏幕文本写入日志（任务书硬性规则 6）。
 */
public interface Logger {
    public fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    )

    public fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    )

    public fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable? = null,
    )

    public fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable? = null,
    )
}

/**
 * 屏幕文本脱敏（docs/tech/02 §1.4）：输出 `len=12 h=3fa2b1c0`——长度 + SHA-256 前 4 字节，
 * 既能比对“是否同一段文本”，又不泄露内容。
 */
public object LogRedact {
    private const val HASH_HEX_CHARS = 8

    public fun text(s: String?): String {
        if (s.isNullOrEmpty()) return "len=0 h=-"
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString(separator = "") { "%02x".format(it) }.take(HASH_HEX_CHARS)
        return "len=${s.length} h=$hash"
    }
}
