package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** input 提示语判定（vp9 §4.6）：亮度差 < hintContrast → hint。 */
internal class InputHintDetectorTest {
    private fun solid(
        w: Int,
        h: Int,
        color: Int,
    ): VisionImage = VisionImage(w, h, IntArray(w * h) { color })

    private fun rgb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `high contrast text is not hint`() {
        // 黑底白字：亮度差 255。
        val pixels = IntArray(40 * 20) { rgb(0, 0, 0) }
        for (y in 5..14) {
            for (x in 5..34) pixels[y * 40 + x] = rgb(255, 255, 255)
        }
        val image = VisionImage(40, 20, pixels)
        assertThat(InputHintDetector.isHint(image, Rect(0, 0, 40, 20), 60)).isFalse()
    }

    @Test
    fun `low contrast text is hint`() {
        // 灰底浅灰字：亮度差约 20。
        val pixels = IntArray(40 * 20) { rgb(120, 120, 120) }
        for (y in 5..14) {
            for (x in 5..34) pixels[y * 40 + x] = rgb(140, 140, 140)
        }
        val image = VisionImage(40, 20, pixels)
        assertThat(InputHintDetector.isHint(image, Rect(0, 0, 40, 20), 60)).isTrue()
    }

    @Test
    fun `solid box is hint`() {
        val image = solid(20, 10, rgb(128, 128, 128))
        assertThat(InputHintDetector.isHint(image, Rect(0, 0, 20, 10), 60)).isTrue()
    }
}
