package com.example.autop.core.common

/**
 * 中文二元组（bigram）分词（docs/tech/adr/0005，差异 D2）。
 *
 * 系统 SQLite 不保证启用 FTS5，全文索引用 FTS4 + 应用侧分词：写入前把文本切成二元组
 * 存入分词列，查询时对 query 做同样处理。算法集中在本纯函数，替换分词策略只改这里。
 *
 * 规则：连续 CJK 段按相邻两字滑窗切二元组（单字段保留原字）；ASCII 字母数字段作为
 * 一个词并小写化；其余字符（标点、空白、emoji）是分隔符，不产出词元。
 */
public object BigramTokenizer {
    public fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        var bufferIsCjk = false

        fun flush() {
            if (buffer.isEmpty()) return
            if (bufferIsCjk) {
                tokens += cjkBigrams(buffer.toString())
            } else {
                tokens += buffer.toString().lowercase()
            }
            buffer.clear()
        }
        for (ch in text) {
            val isCjk = isCjkChar(ch)
            val isWordChar = isCjk || ch.isLetterOrDigit()
            when {
                !isWordChar -> flush()
                buffer.isEmpty() -> {
                    buffer.append(ch)
                    bufferIsCjk = isCjk
                }
                isCjk == bufferIsCjk -> buffer.append(ch)
                else -> {
                    flush()
                    buffer.append(ch)
                    bufferIsCjk = isCjk
                }
            }
        }
        flush()
        return tokens
    }

    /** 供 FTS4 分词列写入：词元以空格连接。 */
    public fun joinForIndex(text: String): String = tokenize(text).joinToString(separator = " ")

    private fun cjkBigrams(segment: String): List<String> =
        if (segment.length == 1) {
            listOf(segment)
        } else {
            List(segment.length - 1) { i -> segment.substring(i, i + 2) }
        }

    /** CJK 表意文字判定（含扩展 A 与兼容区），供分词与 token 估算共用。 */
    public fun isCjkChar(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
