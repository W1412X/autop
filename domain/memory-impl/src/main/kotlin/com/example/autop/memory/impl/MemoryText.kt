/**
 * 记忆文本纯函数（docs/tech/06 §2.4/§2.5，ADR-0028）：摘要截取、向量输入文本、内容指纹。
 */
package com.example.autop.memory.impl

import java.security.MessageDigest

/** 记忆文本处理（纯函数，JVM 可测）。 */
public object MemoryText {
    /** 摘要：去掉 Markdown 标记后取前 [maxChars] 字。 */
    public fun summarize(
        content: String,
        maxChars: Int,
    ): String {
        val plain = stripMarkdown(content).trim()
        return if (plain.length <= maxChars) plain else plain.substring(0, maxChars)
    }

    /**
     * 向量化输入：标题与关键词（规范化后空格连接）。摘要与正文不参与向量化，
     * 避免变化频繁、语义分散的文本稀释关键词信号。
     */
    public fun embeddingText(
        title: String,
        keywords: List<String>,
    ): String = (listOf(title) + keywords).map(MemoryKeywords::normalize).filter { it.isNotEmpty() }.joinToString(" ")

    /** 内容指纹（sha-256 hex）：向量化输入未变化则跳过重新向量化。 */
    public fun textHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun stripMarkdown(text: String): String =
        text
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_`>~]"), "")
            .replace(Regex("\\[([^]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("\\s+"), " ")
}
