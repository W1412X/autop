package com.example.autop.device.impl.shell

/**
 * 命令参数的合法性校验（shell-exec 设计 §9.3）。
 *
 * 这里只检查参数是否可以安全地作为一条命令发送，**不做命令内容审查**：
 * 不检查程序名、管道、引号或命令替换。危险操作规则另行处理，只决定是否需要确认。
 */
internal object ShellCommandRules {
    /** 返回第一个不合法的原因；合法返回 null。 */
    fun problemOf(
        command: String,
        maxBytes: Int,
    ): String? {
        if (command.isEmpty()) return "command is empty"
        if (command.toByteArray(Charsets.UTF_8).size > maxBytes) return "command exceeds $maxBytes bytes"
        val firstBad = command.codePoints().toArray().firstOrNull { isForbidden(it) }
        return firstBad?.let { "command contains forbidden code point U+%04X".format(it) }
    }

    /**
     * 禁止的码点：NUL；除 `\n` `\r` `\t` 外的 C0 控制字符；DEL；C1 控制字符；
     * Unicode 双向控制字符（LRE/RLE/PDF/LRO/RLO 与 LRI/RLI/FSI/PDI）。
     */
    fun isForbidden(codePoint: Int): Boolean =
        when {
            codePoint == NUL -> true
            codePoint < CONTROL_LIMIT -> codePoint != LINE_FEED && codePoint != CARRIAGE_RETURN && codePoint != TAB
            codePoint == DEL -> true
            codePoint in C1_FIRST..C1_LAST -> true
            codePoint in BIDI_EMBED_FIRST..BIDI_EMBED_LAST -> true
            codePoint in BIDI_ISOLATE_FIRST..BIDI_ISOLATE_LAST -> true
            else -> false
        }

    private const val NUL = 0x00
    private const val CONTROL_LIMIT = 0x20
    private const val TAB = 0x09
    private const val LINE_FEED = 0x0A
    private const val CARRIAGE_RETURN = 0x0D
    private const val DEL = 0x7F
    private const val C1_FIRST = 0x80
    private const val C1_LAST = 0x9F
    private const val BIDI_EMBED_FIRST = 0x202A
    private const val BIDI_EMBED_LAST = 0x202E
    private const val BIDI_ISOLATE_FIRST = 0x2066
    private const val BIDI_ISOLATE_LAST = 0x2069
}
