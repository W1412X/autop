package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextTokenEstimatorTest {
    private val cjkFactor = 1.5
    private val charsPerToken = 4.0

    @Test
    fun `empty text estimates zero`() {
        assertThat(TextTokenEstimator.estimate("", cjkFactor, charsPerToken)).isEqualTo(0)
    }

    @Test
    fun `cjk text uses cjk factor`() {
        // 30 个 CJK 字 / 1.5 = 20 tokens
        assertThat(TextTokenEstimator.estimate("设".repeat(30), cjkFactor, charsPerToken))
            .isEqualTo(20)
    }

    @Test
    fun `ascii text uses chars per token`() {
        // 40 个字符 / 4.0 = 10 tokens
        assertThat(TextTokenEstimator.estimate("a".repeat(40), cjkFactor, charsPerToken))
            .isEqualTo(10)
    }

    @Test
    fun `mixed text sums both parts`() {
        val text = "设".repeat(3) + "abc"
        // 3/1.5 + 3/4.0 = 2.75 → 3
        assertThat(TextTokenEstimator.estimate(text, cjkFactor, charsPerToken)).isEqualTo(3)
    }

    @Test
    fun `estimate is stable for same input`() {
        val text = "点击设置按钮，然后等待 2 秒"
        val first = TextTokenEstimator.estimate(text, cjkFactor, charsPerToken)
        val second = TextTokenEstimator.estimate(text, cjkFactor, charsPerToken)
        assertThat(first).isEqualTo(second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non positive chars per token is rejected`() {
        TextTokenEstimator.estimate("abc", cjkFactor, 0.0)
    }
}
