/**
 * 关键词规范化与匹配规则（ADR-0028）。纯函数，JVM 可测。
 *
 * 关键词只用于检索：规范化后存储（NFKC、小写、去首尾空白、折叠空白、去重）；
 * 参与匹配的关键词必须达到长度下限，避免 1 字中文或短英文造成大量误命中。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.BigramTokenizer
import java.text.Normalizer

/** 关键词与检索文本的规范化、长度判断与约束校验。 */
public object MemoryKeywords {
    private val whitespace = Regex("\\s+")

    /** 文本规范化：NFKC、小写、去首尾空白、折叠内部空白。 */
    public fun normalize(raw: String): String =
        Normalizer
            .normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .lowercase()
            .replace(whitespace, " ")

    /** 关键词列表规范化：规范化后去空、去重，保持首次出现的顺序。 */
    public fun normalizeList(raw: List<String>): List<String> =
        raw.map(::normalize).filter { it.isNotEmpty() }.distinct()

    /** 是否达到参与匹配的长度：含 CJK 字符时不少于 [minCjkChars]，否则不少于 [minAsciiChars]。 */
    public fun isUsable(
        term: String,
        minCjkChars: Int,
        minAsciiChars: Int,
    ): Boolean {
        val hasCjk = term.any { BigramTokenizer.isCjkChar(it) }
        return term.length >= if (hasCjk) minCjkChars else minAsciiChars
    }

    /** 约束校验：返回错误说明，合法返回 null。输入应已规范化。 */
    public fun validate(
        keywords: List<String>,
        maxCount: Int,
        maxChars: Int,
    ): String? =
        when {
            keywords.size > maxCount -> "关键词最多 $maxCount 个"
            keywords.any { it.length > maxChars } -> "单个关键词最多 $maxChars 字"
            else -> null
        }
}
