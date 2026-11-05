package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 图标分类编解码（vp9 §5.3）：letterbox 几何、softmax＋拒识、metadata 校验。 */
internal class IconClsWireTest {
    private fun rgb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `letterbox pads gray around non-square region`() {
        val image = VisionImage(2, 1, intArrayOf(rgb(255, 0, 0), rgb(255, 0, 0)))
        val out = IconClsWire.letterbox(image, Rect(0, 0, 2, 1))
        val plane = IconClsWire.INPUT_SIZE * IconClsWire.INPUT_SIZE
        assertThat(out).hasLength(3 * plane)
        val center = 48 * IconClsWire.INPUT_SIZE + 48
        // 内容区（中线）：纯红 → R≈(1−0.485)/0.229、G≈−0.456/0.224、B≈−0.406/0.225。
        assertThat(out[center]).isWithin(1e-3f).of((1.0f - 0.485f) / 0.229f)
        assertThat(out[plane + center]).isWithin(1e-3f).of((0.0f - 0.456f) / 0.224f)
        assertThat(out[2 * plane + center]).isWithin(1e-3f).of((0.0f - 0.406f) / 0.225f)
        // 角落：补灰 114。
        assertThat(out[0]).isWithin(1e-3f).of((114f / 255f - 0.485f) / 0.229f)
    }

    @Test
    fun `decode returns top key when confident`() {
        val logits = floatArrayOf(10f, 0f, 0f)
        val out = IconClsWire.decode(logits, listOf("search", "add", "settings"), emptySet())
        assertThat(out).isEqualTo("search")
    }

    @Test
    fun `decode rejects reject label`() {
        val logits = floatArrayOf(10f, 0f)
        val out = IconClsWire.decode(logits, listOf("text_crop", "search"), setOf("text_crop", "image_crop"))
        assertThat(out).isNull()
    }

    @Test
    fun `decode rejects low confidence and small margin`() {
        val flat = IconClsWire.decode(floatArrayOf(0f, 0f, 0f), listOf("a", "b", "c"), emptySet())
        assertThat(flat).isNull()
        val close = IconClsWire.decode(floatArrayOf(0.05f, 0f, 0f), listOf("a", "b", "c"), emptySet())
        assertThat(close).isNull()
    }

    @Test
    fun `metadata ok requires size and imagenet stats`() {
        val good =
            mapOf(
                "img_size" to "96x96",
                "normalize_mean" to "[0.485, 0.456, 0.406]",
                "normalize_std" to "[0.229, 0.224, 0.225]",
            )
        assertThat(IconClsWire.metadataOk(good)).isTrue()
        assertThat(IconClsWire.metadataOk(good + ("img_size" to "128x128"))).isFalse()
        assertThat(IconClsWire.metadataOk(good - "normalize_std")).isFalse()
        assertThat(IconClsWire.metadataOk(good + ("normalize_mean" to "[0.5, 0.5, 0.5]"))).isFalse()
    }
}
