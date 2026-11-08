package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** rec 双档级联（ocr-accuracy-uplift 阶段 B）：低置信行升级、名额截断、档位与回退。 */
internal class TieredTextRecognizerTest {
    private val image = VisionImage(1, 1, IntArray(1))

    /** 识别桩：按框索引（bounds.l）回放预设结果并计数调用。 */
    private class FakeRecognizer(
        private val readyFlag: Boolean = true,
        private val result: (Int) -> VisualOcrLine?,
    ) : TextRecognizer {
        var calls: Int = 0

        override val ready: Boolean
            get() = readyFlag

        override fun recognize(
            image: VisionImage,
            box: OcrBox,
        ): VisualOcrLine? {
            calls++
            return result(box.bounds.l)
        }
    }

    @Test
    fun `cascade upgrades lowest confidence lines within quota`() {
        val confs = listOf(0.95, 0.5, 0.4, 0.3)
        val fast = FakeRecognizer { i -> line("F$i", confs[i]) }
        val strong = FakeRecognizer { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val boxes = List(confs.size) { box(it) }
        val lines = tiered.recognizeAll(image, boxes, RecCascadeOptions(RecCascadeMode.CASCADE, 0.6, 2))
        // 名额 2：只升级置信度最低的 0.3、0.4 两行，其余保持快档。
        assertThat(lines.map { it?.text }).isEqualTo(listOf("F0", "F1", "S2", "S3"))
        assertThat(strong.calls).isEqualTo(2)
    }

    @Test
    fun `high confidence lines stay on fast path`() {
        val fast = FakeRecognizer { i -> line("F$i", 0.95) }
        val strong = FakeRecognizer { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(3) { box(it) }, RecCascadeOptions(RecCascadeMode.CASCADE, 0.6, 6))
        assertThat(lines.map { it?.text }).isEqualTo(listOf("F0", "F1", "F2"))
        assertThat(strong.calls).isEqualTo(0)
    }

    @Test
    fun `mobile mode never upgrades`() {
        val fast = FakeRecognizer { i -> line("F$i", 0.1) }
        val strong = FakeRecognizer { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(2) { box(it) }, RecCascadeOptions(RecCascadeMode.MOBILE, 0.6, 6))
        assertThat(lines.map { it?.text }).isEqualTo(listOf("F0", "F1"))
        assertThat(strong.calls).isEqualTo(0)
    }

    @Test
    fun `server mode upgrades every line`() {
        val fast = FakeRecognizer { i -> line("F$i", 0.95) }
        val strong = FakeRecognizer { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(3) { box(it) }, RecCascadeOptions(RecCascadeMode.SERVER, 0.6, 6))
        assertThat(lines.map { it?.text }).isEqualTo(listOf("S0", "S1", "S2"))
        assertThat(strong.calls).isEqualTo(3)
    }

    @Test
    fun `missing strong falls back to fast tier`() {
        val fast = FakeRecognizer { i -> line("F$i", 0.1) }
        val strong = FakeRecognizer(readyFlag = false) { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(2) { box(it) }, RecCascadeOptions(RecCascadeMode.CASCADE, 0.6, 6))
        assertThat(lines.map { it?.text }).isEqualTo(listOf("F0", "F1"))
        assertThat(strong.calls).isEqualTo(0)
    }

    @Test
    fun `null fast result is upgraded too`() {
        val fast = FakeRecognizer { i -> if (i == 0) null else line("F$i", 0.95) }
        val strong = FakeRecognizer { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(2) { box(it) }, RecCascadeOptions(RecCascadeMode.CASCADE, 0.6, 6))
        assertThat(lines.map { it?.text }).isEqualTo(listOf("S0", "F1"))
    }

    @Test
    fun `strong failure keeps fast result`() {
        val fast = FakeRecognizer { i -> line("F$i", 0.1) }
        val strong = FakeRecognizer { null }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(2) { box(it) }, RecCascadeOptions(RecCascadeMode.CASCADE, 0.6, 6))
        assertThat(lines.map { it?.text }).isEqualTo(listOf("F0", "F1"))
    }

    @Test
    fun `not ready returns null lines without calls`() {
        val fast = FakeRecognizer(readyFlag = false) { i -> line("F$i", 0.9) }
        val strong = FakeRecognizer { i -> line("S$i", 0.99) }
        val tiered = TieredTextRecognizer(fast, strong)
        val lines = tiered.recognizeAll(image, List(2) { box(it) }, RecCascadeOptions())
        assertThat(lines).containsExactly(null, null)
        assertThat(fast.calls).isEqualTo(0)
        assertThat(strong.calls).isEqualTo(0)
    }

    private fun box(at: Int): OcrBox = OcrBox(Rect(at, at, at + 1, at + 1), 0.9)

    private fun line(
        text: String,
        conf: Double,
    ): VisualOcrLine = VisualOcrLine(text, conf, Rect(0, 0, 1, 1))
}
