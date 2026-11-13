/**
 * WordPieceTokenizer 与 batch 对齐的单测（小词表，纯 JVM）。
 * 与上游一致的完整对照见 [TokenizerParityTest]。
 */
package com.example.autop.llm.onnx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocab =
        mapOf(
            "[PAD]" to 0,
            "[UNK]" to 1,
            "[CLS]" to 2,
            "[SEP]" to 3,
            "张" to 4,
            "三" to 5,
            "hello" to 6,
            "," to 9,
            "play" to 8,
            "##ing" to 7,
        )
    private val tokenizer = WordPieceTokenizer(vocab)

    @Test
    fun `cjk characters are split one per token`() {
        assertThat(tokenizer.tokenize("张三").toList()).containsExactly(4, 5).inOrder()
    }

    @Test
    fun `punctuation is isolated as its own token`() {
        assertThat(tokenizer.tokenize("hello, 张").toList()).containsExactly(6, 9, 4).inOrder()
    }

    @Test
    fun `continuation pieces use the double hash prefix`() {
        assertThat(tokenizer.tokenize("playing").toList()).containsExactly(8, 7).inOrder()
    }

    @Test
    fun `a word that cannot be fully matched becomes unk as a whole`() {
        assertThat(tokenizer.tokenize("xyz").toList()).containsExactly(1)
    }

    @Test
    fun `text is not lowercased so uppercase letters are unk`() {
        assertThat(tokenizer.tokenize("Hello").toList()).containsExactly(1)
    }

    @Test
    fun `control characters are removed and tabs or newlines act as spaces`() {
        assertThat(tokenizer.tokenize("张\u200b三").toList()).containsExactly(4, 5).inOrder()
        assertThat(tokenizer.tokenize("张\t\n三").toList()).containsExactly(4, 5).inOrder()
    }

    @Test
    fun `encode adds cls and sep and truncates to the max sequence length`() {
        assertThat(tokenizer.encode("张三张三", maxSeqLen = 4).toList()).containsExactly(2, 4, 5, 3).inOrder()
    }

    @Test
    fun `encode of empty text is cls followed by sep`() {
        assertThat(tokenizer.encode("", maxSeqLen = 64).toList()).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `padBatch pads to the longest row and masks the padding`() {
        val batch = padBatch(listOf(intArrayOf(2, 4, 3), intArrayOf(2, 3)), padId = 0)

        assertThat(batch.batch).isEqualTo(2)
        assertThat(batch.seqLen).isEqualTo(3)
        assertThat(batch.inputIds.toList()).containsExactly(2L, 4L, 3L, 2L, 3L, 0L).inOrder()
        assertThat(batch.attentionMask.toList()).containsExactly(1L, 1L, 1L, 1L, 1L, 0L).inOrder()
        assertThat(batch.tokenTypeIds.all { it == 0L }).isTrue()
    }

    @Test
    fun `normalized vector has unit length and zero vector is unchanged`() {
        val unit = floatArrayOf(3f, 4f).normalized()

        assertThat(unit[0]).isWithin(1e-6f).of(0.6f)
        assertThat(unit[1]).isWithin(1e-6f).of(0.8f)
        assertThat(floatArrayOf(0f, 0f).normalized().toList()).containsExactly(0f, 0f).inOrder()
    }
}
