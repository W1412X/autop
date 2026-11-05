package com.example.autop.core.platform

import com.example.autop.core.common.LogRedact

/**
 * 日志行的纯格式化与脱敏（docs/tech/02 §4.4、任务书硬性规则 6）。
 *
 * `kv` 中 key 以 `text`、`content` 结尾（大小写不敏感，`nodeText` 同样命中，安全从严）的值
 * 一律经 [LogRedact.text] 脱敏——屏幕文本属于敏感数据，任何构建都不落原文（见 questions.md N15）。
 */
internal object LogLineFormatter {
    private const val NULL_VALUE = "-"

    fun format(
        timestamp: Long,
        event: String,
        kv: Array<out Pair<String, Any?>>,
        error: Throwable?,
    ): String =
        buildString {
            append(timestamp).append(' ').append(event)
            for ((key, value) in kv) {
                append(' ').append(key).append('=').append(formatValue(key, value))
            }
            if (error != null) {
                append(" error=").append(error::class.simpleName ?: "Throwable")
                error.message?.let { append(": ").append(it) }
                append('\n').append(stackOf(error))
            }
        }

    /**
     * 堆栈（含 Caused by 链）截断到 [MAX_STACK_LINES] 行：取证够用，
     * 又不撑爆环形缓冲/单条 logcat（questions.md N165）。
     */
    private fun stackOf(error: Throwable): String {
        val lines = error.stackTraceToString().lines()
        val head = lines.take(MAX_STACK_LINES)
        return (if (lines.size > MAX_STACK_LINES) head + TRUNCATED else head).joinToString("\n")
    }

    private fun formatValue(
        key: String,
        value: Any?,
    ): String =
        if (key.endsWith(TEXT_SUFFIX, ignoreCase = true) || key.endsWith(CONTENT_SUFFIX, ignoreCase = true)) {
            LogRedact.text(value?.toString())
        } else {
            value?.toString() ?: NULL_VALUE
        }

    private const val TEXT_SUFFIX = "text"
    private const val CONTENT_SUFFIX = "content"
    private const val MAX_STACK_LINES = 24
    private const val TRUNCATED = "\t…（堆栈截断）"
}
