/**
 * MemoryText 测试（docs/tech/06 §2.4/§2.5）：摘要截取、embedding 切块、内容指纹。
 */
package com.example.autop.memory.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 记忆文本纯函数行为测试。 */
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
    public fun `chunkForEmbedding packs paragraphs under budget with prefix`() {
        val chunks = MemoryText.chunkForEmbedding("标题", "/a/b", "第一段\n\n第二段", chunkChars = 100)

        assertThat(chunks).hasSize(1)
        assertThat(chunks.single()).isEqualTo("[标题](/a/b)\n第一段\n\n第二段")
    }

    @Test
    public fun `chunkForEmbedding splits long paragraph list`() {
        val content = "aaaaaaaaaa\n\nbbbbbbbbbb\n\ncccccccccc"

        val chunks = MemoryText.chunkForEmbedding("t", "/p", content, chunkChars = 24)

        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach { assertThat(it).startsWith("[t](/p)\n") }
    }

    @Test
    public fun `textHash is stable and content sensitive`() {
        assertThat(MemoryText.textHash("内容")).isEqualTo(MemoryText.textHash("内容"))
        assertThat(MemoryText.textHash("内容")).isNotEqualTo(MemoryText.textHash("其它"))
    }
}
