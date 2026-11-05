package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 裁剪与拼图（vp9 §4.4）：外扩不越邻、det 放大、装箱、坐标回映。 */
internal class CropMosaicTest {
    @Test
    fun `crop region expands and clamps to screen`() {
        val out = CropMosaic.cropRegion(Rect(100, 100, 200, 200), emptyList(), 0.1, 1080, 1920)
        assertThat(out).isEqualTo(Rect(90, 90, 210, 210))
    }

    @Test
    fun `crop region never crosses neighbor edges`() {
        val left = Rect(50, 100, 90, 200)
        val out = CropMosaic.cropRegion(Rect(100, 100, 200, 200), listOf(left), 0.5, 1080, 1920)
        assertThat(out.l).isEqualTo(90)
        assertThat(out.t).isEqualTo(50)
        assertThat(out.r).isEqualTo(250)
        assertThat(out.b).isEqualTo(250)
    }

    @Test
    fun `det scale raises small text and caps at max`() {
        assertThat(CropMosaic.detScale(16, 32, 3.0)).isEqualTo(2.0)
        assertThat(CropMosaic.detScale(4, 32, 3.0)).isEqualTo(3.0)
        assertThat(CropMosaic.detScale(null, 32, 3.0)).isEqualTo(1.0)
    }

    @Test
    fun `mosaic packs blocks in height order with gap`() {
        val crops =
            listOf(
                RescanCrop(0, Rect(0, 0, 100, 50)),
                RescanCrop(1, Rect(0, 100, 100, 200)),
            )
        val mosaic = CropMosaic.mosaic(crops, mapOf(0 to 1.0, 1 to 1.0), 24)
        // 高块（100）在前：slot0=box1，slot1=box0；同排水平排开。
        assertThat(mosaic.slots.map { it.boxIndex }).containsExactly(1, 0).inOrder()
        assertThat(mosaic.slots[0].canvas).isEqualTo(Rect(0, 0, 100, 100))
        assertThat(mosaic.slots[1].canvas).isEqualTo(Rect(124, 0, 224, 50))
    }

    @Test
    fun `map back converts canvas box to screen coords`() {
        val slot = MosaicSlot(0, Rect(124, 0, 224, 100), Rect(500, 600, 600, 700), 1.0)
        val out = CropMosaic.mapBack(Rect(134, 10, 184, 60), slot)
        assertThat(out).isEqualTo(Rect(510, 610, 560, 660))
    }

    @Test
    fun `map back divides by scale`() {
        val slot = MosaicSlot(0, Rect(0, 0, 200, 200), Rect(500, 600, 600, 700), 2.0)
        val out = CropMosaic.mapBack(Rect(10, 20, 30, 40), slot)
        assertThat(out).isEqualTo(Rect(505, 610, 515, 620))
    }
}
