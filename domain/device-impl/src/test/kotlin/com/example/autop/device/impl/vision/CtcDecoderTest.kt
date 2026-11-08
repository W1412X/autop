package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** CTC 解码增强（ocr-accuracy-uplift 阶段 A）：beam＋词典重打分纠形近字、行级置信度口径。 */
internal class CtcDecoderTest {
    private val charset = listOf("王", "信", "微")

    /** t0 王 0.51 / 微 0.49（形近竞争），t1 信 1.0：贪心出「王信」，词典把「微信」拉回来。 */
    private val nearGlyphLogits = floatArrayOf(0.0f, 0.51f, 0.0f, 0.49f, 0.0f, 0.0f, 1.0f, 0.0f)

    @Test
    fun `beam with lexicon corrects near-glyph confusion`() {
        val options = CtcDecodeOptions(beamWidth = 5, lexicon = setOf("微信"), lmWeight = 0.5)
        val result = CtcDecoder.decode(nearGlyphLogits, 2, 4, charset, options)
        assertThat(result.text).isEqualTo("微信")
    }

    @Test
    fun `beam without lexicon keeps acoustic best`() {
        val result = CtcDecoder.decode(nearGlyphLogits, 2, 4, charset, CtcDecodeOptions(lexicon = emptySet()))
        assertThat(result.text).isEqualTo("王信")
    }

    @Test
    fun `beam width one falls back to greedy`() {
        val result = CtcDecoder.decode(nearGlyphLogits, 2, 4, charset, CtcDecodeOptions(beamWidth = 1))
        assertThat(result.text).isEqualTo(PpOcrCodec.ctcDecode(nearGlyphLogits, 2, 4, charset))
    }

    @Test
    fun `confidence is mean of greedy emitted char probs`() {
        val charset = listOf("a", "b")
        val logits = FloatArray(5 * 3)
        putRow(logits, 0, 3, floatArrayOf(1.0f, 0.0f, 0.0f))
        putRow(logits, 1, 3, floatArrayOf(0.1f, 0.9f, 0.0f))
        putRow(logits, 2, 3, floatArrayOf(0.4f, 0.6f, 0.0f))
        putRow(logits, 3, 3, floatArrayOf(0.2f, 0.0f, 0.8f))
        putRow(logits, 4, 3, floatArrayOf(1.0f, 0.0f, 0.0f))
        // 发射步 t1（a 0.9）与 t3（b 0.8），t2 重复不计 → (0.9+0.8)/2。
        val result = CtcDecoder.decode(logits, 5, 3, charset, CtcDecodeOptions(beamWidth = 1))
        assertThat(result.text).isEqualTo("ab")
        assertThat(result.confidence).isWithin(1e-6).of(0.85)
    }

    @Test
    fun `all blank row decodes empty with zero confidence`() {
        val logits = FloatArray(3 * 4)
        for (t in 0 until 3) putRow(logits, t, 4, floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f))
        val result = CtcDecoder.decode(logits, 3, 4, charset)
        assertThat(result.text).isEmpty()
        assertThat(result.confidence).isEqualTo(0.0)
    }

    @Test
    fun `logit rows are normalized via softmax path`() {
        // 非概率行（值不归一）走 log-softmax：t0 王 大幅领先、t1 信 大幅领先。
        val logits = FloatArray(2 * 4)
        putRow(logits, 0, 4, floatArrayOf(0.0f, 10.0f, 0.0f, 0.0f))
        putRow(logits, 1, 4, floatArrayOf(0.0f, 0.0f, 10.0f, 0.0f))
        val result = CtcDecoder.decode(logits, 2, 4, charset, CtcDecodeOptions(beamWidth = 1))
        assertThat(result.text).isEqualTo("王信")
        assertThat(result.confidence).isGreaterThan(0.9)
    }

    @Test
    fun `lexicon parsing trims lines and drops short entries`() {
        val lexicon = CtcDecoder.lexiconOf(" 微信\n设置\n王\n\n朋友圈\n")
        assertThat(lexicon).containsExactly("微信", "设置", "朋友圈")
    }

    private fun putRow(
        logits: FloatArray,
        t: Int,
        classes: Int,
        row: FloatArray,
    ) {
        row.copyInto(logits, t * classes)
    }
}
