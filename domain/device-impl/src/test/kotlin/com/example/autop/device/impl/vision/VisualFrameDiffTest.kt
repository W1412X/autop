package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 帧差感知（设计稿 §7）：aHash 汉明距离＋全局 SSIM 无变化判据。 */
internal class VisualFrameDiffTest {
    private val maxHamming: Int = 6
    private val minSsim: Double = 0.98

    private fun flat(value: Int): IntArray = IntArray(VisualFrameDiff.GRAY_EDGE * VisualFrameDiff.GRAY_EDGE) { value }

    private fun halves(
        top: Int,
        bottom: Int,
    ): IntArray {
        val edge = VisualFrameDiff.GRAY_EDGE
        return IntArray(edge * edge) { i -> if (i / edge < edge / 2) top else bottom }
    }

    @Test
    fun `identical frames are unchanged`() {
        val a = VisualFrameDiff.signature(flat(100))
        val b = VisualFrameDiff.signature(flat(100))
        assertThat(VisualFrameDiff.unchanged(a, b, maxHamming, minSsim)).isTrue()
        assertThat(VisualFrameDiff.hamming(a.hash, b.hash)).isEqualTo(0)
    }

    @Test
    fun `brightness shift fails ssim gate`() {
        val a = VisualFrameDiff.signature(flat(100))
        val b = VisualFrameDiff.signature(flat(200))
        assertThat(VisualFrameDiff.ssim(a.gray, b.gray)).isLessThan(minSsim)
        assertThat(VisualFrameDiff.unchanged(a, b, maxHamming, minSsim)).isFalse()
    }

    @Test
    fun `content swap flips hash bits`() {
        val a = VisualFrameDiff.signature(halves(200, 0))
        val b = VisualFrameDiff.signature(halves(0, 200))
        assertThat(VisualFrameDiff.hamming(a.hash, b.hash)).isEqualTo(64)
        assertThat(VisualFrameDiff.unchanged(a, b, maxHamming, minSsim)).isFalse()
    }

    @Test
    fun `hamming counts set xor bits`() {
        assertThat(VisualFrameDiff.hamming(0, -1L)).isEqualTo(64)
        assertThat(VisualFrameDiff.hamming(0b101, 0b001)).isEqualTo(1)
    }

    @Test
    fun `ssim of identical arrays is one`() {
        val gray = flat(128)
        assertThat(VisualFrameDiff.ssim(gray, gray)).isWithin(1e-9).of(1.0)
    }
}
