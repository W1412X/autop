/**
 * [parseMarkdown] / [inlineMarkdown] 单测（docs/tech/08 §4.2 Markdown 渲染：块级
 * 标题 / 列表 / 引用 / 代码块 / 分隔线 / 表格与行内强调，未闭合标记不得误判）。
 */
package com.example.autop.core.ui.component

import androidx.compose.ui.text.SpanStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownTest {
    @Test
    fun `blocks parse into heading list quote divider`() {
        val text =
            """
            # 标题
            - 项目一
            1. 有序项
            > 引用语
            ---
            """.trimIndent()

        val blocks = parseMarkdown(text)

        assertThat(blocks)
            .containsExactly(
                MdBlock.Heading(1, "标题"),
                MdBlock.Item("•", "项目一"),
                MdBlock.Item("1.", "有序项"),
                MdBlock.Quote("引用语"),
                MdBlock.Divider,
            ).inOrder()
    }

    @Test
    fun `fenced code block keeps body and drops fence and language`() {
        val blocks =
            parseMarkdown(
                """
                ```kotlin
                val a = 1
                val b = 2
                ```
                """.trimIndent(),
            )

        assertThat(blocks).containsExactly(MdBlock.Code("val a = 1\nval b = 2"))
    }

    @Test
    fun `paragraph keeps soft breaks and stops at next block`() {
        val blocks =
            parseMarkdown(
                """
                第一行
                第二行

                ## 小标题
                """.trimIndent(),
            )

        assertThat(blocks)
            .containsExactly(
                MdBlock.Paragraph("第一行\n第二行"),
                MdBlock.Heading(2, "小标题"),
            ).inOrder()
    }

    @Test
    fun `table parses header alignment and rows`() {
        val blocks =
            parseMarkdown(
                """
                | 名称 | 数量 | 说明 |
                | :--- | ---: | :---: |
                | 苹果 | 3 | 红 |
                | 梨 | 12 |
                """.trimIndent(),
            )

        assertThat(blocks)
            .containsExactly(
                MdBlock.Table(
                    headers = listOf("名称", "数量", "说明"),
                    aligns = listOf(MdAlign.START, MdAlign.END, MdAlign.CENTER),
                    rows = listOf(listOf("苹果", "3", "红"), listOf("梨", "12", "")),
                ),
            )
    }

    @Test
    fun `paragraph before table stops at table header`() {
        val blocks =
            parseMarkdown(
                """
                下面是结果：
                | 项目 | 值 |
                | --- | --- |
                | a | 1 |
                """.trimIndent(),
            )

        assertThat(blocks)
            .containsExactly(
                MdBlock.Paragraph("下面是结果："),
                MdBlock.Table(
                    headers = listOf("项目", "值"),
                    aligns = listOf(MdAlign.START, MdAlign.START),
                    rows = listOf(listOf("a", "1")),
                ),
            ).inOrder()
    }

    @Test
    fun `heading without space and chinese numbered list`() {
        val blocks = parseMarkdown("###标题\n1、第一步\n- [x] 已完成\n- [ ] 待办")

        assertThat(blocks)
            .containsExactly(
                MdBlock.Heading(3, "标题"),
                MdBlock.Item("1.", "第一步"),
                MdBlock.Item("☑", "已完成"),
                MdBlock.Item("☐", "待办"),
            ).inOrder()
    }

    @Test
    fun `inline markers become spans and text keeps content`() {
        val result = inlineMarkdown("**粗** *斜* `码` ~~删~~ [链](https://x)", SpanStyle(), SpanStyle())

        assertThat(result.text).isEqualTo("粗 斜 码 删 链")
        assertThat(result.spanStyles).hasSize(5)
    }

    @Test
    fun `unclosed markers stay plain`() {
        val result = inlineMarkdown("**未闭合 和 snake_case_word", SpanStyle(), SpanStyle())

        assertThat(result.text).isEqualTo("**未闭合 和 snake_case_word")
        assertThat(result.spanStyles).isEmpty()
    }

    @Test
    fun `streaming trims half typed marker and its tail`() {
        val blocks = parseMarkdown("结果 **粗").trimUnclosedTail()

        assertThat(blocks).containsExactly(MdBlock.Paragraph("结果 "))
    }

    @Test
    fun `streaming keeps closed markers and literal brackets`() {
        val blocks = parseMarkdown("**粗体** 与 [备注] 说明").trimUnclosedTail()

        assertThat(blocks).containsExactly(MdBlock.Paragraph("**粗体** 与 [备注] 说明"))
    }

    @Test
    fun `streaming cuts at earliest unclosed marker across lines`() {
        val blocks = parseMarkdown("前 **未闭合\n后一行").trimUnclosedTail()

        assertThat(blocks).containsExactly(MdBlock.Paragraph("前 "))
    }

    @Test
    fun `streaming trims half typed code span and link`() {
        val span = parseMarkdown("用 `符 号").trimUnclosedTail()
        val link = parseMarkdown("点 [链](htt").trimUnclosedTail()

        assertThat(span).containsExactly(MdBlock.Paragraph("用 "))
        assertThat(link).containsExactly(MdBlock.Paragraph("点 "))
    }

    @Test
    fun `streaming never trims fenced code body`() {
        val blocks =
            parseMarkdown(
                """
                ```
                a ** b
                ```
                """.trimIndent(),
            ).trimUnclosedTail()

        assertThat(blocks).containsExactly(MdBlock.Code("a ** b"))
    }
}
