/**
 * MemoryText 与 MemoryKeywords 测试（docs/tech/06 §2.4，ADR-0028）：摘要截取、向量输入文本、
 * 内容指纹、关键词规范化与长度规则。
 */
package com.example.autop.memory.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 记忆文本与关键词纯函数行为测试。 */
public class MemoryTextTest {
    @Test
    public fun `summarize strips markdown and truncates`() {
        val content = "# 标题\n**重点**内容 [链接](http://x) 与 `代码`"

        val summary = MemoryText.summarize(content, maxChars = 100)

        assertThat(summary).isEqualTo("标题 重点内容 链接 与 代码")
    }

    @Test
    public fun `summarize respects max chars`() {
        val summary = MemoryText.summarize("abcdefghij", maxChars = 4)

        assertThat(summary).isEqualTo("abcd")
    }

    @Test
    public fun `embedding text is title plus normalized keywords and ignores content`() {
        val text = MemoryText.embeddingText("张三", listOf("Zhang San", " 同事 ", ""))

        assertThat(text).isEqualTo("张三 zhang san 同事")
    }

    @Test
    public fun `textHash is stable and content sensitive`() {
        assertThat(MemoryText.textHash("内容")).isEqualTo(MemoryText.textHash("内容"))
        assertThat(MemoryText.textHash("内容")).isNotEqualTo(MemoryText.textHash("其它"))
    }

    @Test
    public fun `keyword normalization lowercases, trims and dedupes in order`() {
        val list = MemoryKeywords.normalizeList(listOf("  微信 ", "WeChat", "微信", "", "wechat"))

        assertThat(list).containsExactly("微信", "wechat").inOrder()
    }

    @Test
    public fun `short terms are not usable for matching`() {
        assertThat(MemoryKeywords.isUsable("我", minCjkChars = 2, minAsciiChars = 3)).isFalse()
        assertThat(MemoryKeywords.isUsable("张三", minCjkChars = 2, minAsciiChars = 3)).isTrue()
        assertThat(MemoryKeywords.isUsable("ab", minCjkChars = 2, minAsciiChars = 3)).isFalse()
        assertThat(MemoryKeywords.isUsable("abc", minCjkChars = 2, minAsciiChars = 3)).isTrue()
    }

    @Test
    public fun `validate reports too many keywords and too long keyword`() {
        assertThat(MemoryKeywords.validate(listOf("a", "b", "c"), maxCount = 2, maxChars = 20))
            .isEqualTo("关键词最多 2 个")
        assertThat(MemoryKeywords.validate(listOf("x".repeat(21)), maxCount = 2, maxChars = 20))
            .isEqualTo("单个关键词最多 20 字")
        assertThat(MemoryKeywords.validate(listOf("张三"), maxCount = 2, maxChars = 20)).isNull()
    }
}
