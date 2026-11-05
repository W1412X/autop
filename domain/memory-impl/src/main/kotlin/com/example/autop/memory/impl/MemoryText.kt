/**
 * 记忆文本纯函数（docs/tech/06 §2.4/§2.5）：摘要截取、embedding 切块、内容指纹。
 */
package com.example.autop.memory.impl

import java.security.MessageDigest

/** 记忆文本处理（纯函数，JVM 可测）。 */
public object MemoryText {
    /**
     * 摘要：去掉 Markdown 标记后取前 [maxChars] 字。
     */
    public fun summarize(
        content: String,
        maxChars: Int,
    ): String {
        val plain = stripMarkdown(content).trim()
        return if (plain.length <= maxChars) plain else plain.substring(0, maxChars)
    }

    /**
     * embedding 切块（docs/tech/06 §2.5）：按段落累计，单块正文不超过 [chunkChars]，
     * 每块前缀为标题与路径。
     */
    public fun chunkForEmbedding(
        title: String,
        path: String,
        content: String,
        chunkChars: Int,
    ): List<String> {
        val prefix = "[$title]($path)\n"
        val paragraphs = content.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return listOf(prefix + content.trim())
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (p in paragraphs) {
            val para = p.trim()
            if (current.isNotEmpty() && current.length + para.length + 2 > chunkChars) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(para)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.map { prefix + it }
    }

    /** 内容指纹（sha-256 hex）：块未变化则跳过重新向量化。 */
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
