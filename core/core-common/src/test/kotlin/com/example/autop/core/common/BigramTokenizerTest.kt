package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BigramTokenizerTest {
    @Test
    fun `chinese text becomes overlapping bigrams`() {
        assertThat(BigramTokenizer.tokenize("设置页面"))
            .containsExactly("设置", "置页", "页面")
            .inOrder()
    }

    @Test
    fun `single cjk char is kept as one token`() {
        assertThat(BigramTokenizer.tokenize("设")).containsExactly("设")
    }

    @Test
    fun `ascii words are lowercased and kept whole`() {
        assertThat(BigramTokenizer.tokenize("OpenApp WiFi-5"))
            .containsExactly("openapp", "wifi", "5")
            .inOrder()
    }

    @Test
    fun `mixed cjk and ascii split into segments`() {
        assertThat(BigramTokenizer.tokenize("点击OK按钮"))
            .containsExactly("点击", "ok", "按钮")
            .inOrder()
    }

    @Test
    fun `punctuation and whitespace are separators`() {
        assertThat(BigramTokenizer.tokenize("你好，世界！ hello\tworld"))
            .containsExactly("你好", "世界", "hello", "world")
            .inOrder()
    }

    @Test
    fun `empty text produces no tokens`() {
        assertThat(BigramTokenizer.tokenize("")).isEmpty()
    }

    @Test
    fun `joinForIndex joins tokens with spaces`() {
        assertThat(BigramTokenizer.joinForIndex("设置页"))
            .isEqualTo("设置 置页")
    }
}
