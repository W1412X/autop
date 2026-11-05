package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 状态裁剪预处理（vp8 §6.3）：外扩 4px、等比 48×144 补灰 114、ImageNet 归一化、CHW。 */
internal class StateCropCodecTest {
    private val plane = StateCropCodec.CROP_W * StateCropCodec.CROP_H

    /** 纯色图（ARGB）。 */
    private fun solid(
        width: Int,
        height: Int,
        argb: Int,
    ) = VisionImage(width, height, IntArray(width * height) { argb })

    @Test
    fun `tall crop pads left and right with gray`() {
        // 100×50 红图整框：缩放到 96×48，左右各 24 列补灰（114）。
        val image = solid(100, 50, 0xFFFF0000.toInt())
        val out = StateCropCodec.crop(image, Rect(0, 0, 100, 50))
        assertThat(out).hasLength(COLOR_CHANNELS * plane)
        assertThat(out[25 * StateCropCodec.CROP_W + 1]).isWithin(1e-3f).of(GRAY_R)
        assertThat(out[plane + 25 * StateCropCodec.CROP_W + 1]).isWithin(1e-3f).of(GRAY_G)
        assertThat(out[2 * plane + 25 * StateCropCodec.CROP_W + 24]).isWithin(1e-3f).of(RED_B)
    }

    @Test
    fun `wide crop pads top and bottom with gray`() {
        // 300×20 红图整框：缩放到 144×10，上下补灰居中（oy=19）。
        val image = solid(300, 20, 0xFFFF0000.toInt())
        val out = StateCropCodec.crop(image, Rect(0, 0, 300, 20))
        assertThat(out[0]).isWithin(1e-3f).of(GRAY_R)
        assertThat(out[20 * StateCropCodec.CROP_W + 72]).isWithin(1e-3f).of(RED_R)
    }

    @Test
    fun `crop region is expanded by four px and clamped`() {
        // 图 10×10：左 3 列绿、其余红；框 (4,4,6,6) 不外扩则全红，外扩 4px → (0,0,10,10) 后
        // 画布左缘采到绿色（缩放到 48×48 居中，ox=48）。
        val pixels = IntArray(10 * 10) { i -> if (i % 10 < 3) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() }
        val out = StateCropCodec.crop(VisionImage(10, 10, pixels), Rect(4, 4, 6, 6))
        assertThat(out[plane + 24 * StateCropCodec.CROP_W + 48]).isWithin(1e-3f).of(GREEN_G)
        assertThat(out[plane + 24 * StateCropCodec.CROP_W + 72]).isWithin(1e-3f).of(RED_G)
        assertThat(out[24 * StateCropCodec.CROP_W]).isWithin(1e-3f).of(GRAY_R)
    }

    private companion object {
        // ImageNet 归一化期望值（(v/255 - mean) / std）。
        const val RED_R = 2.2489f
        const val RED_G = -2.0357f
        const val RED_B = -1.8044f
        const val GREEN_G = 2.4286f
        const val GRAY_R = -0.1657f
        const val GRAY_G = -0.0399f
    }
}
