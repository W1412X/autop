package com.example.autop.device.impl

import com.example.autop.device.Rect
import com.example.autop.device.core.PixelSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 落点准星的换算与投影（N195）：0–1000 归一化 → 原图像素 → 最终图坐标。 */
class ScreenshotMarksTest {
    @Test
    fun markPointOfProjectsNormalizedCoordsToPixels() {
        val point = markPointOf(PixelSize(1080, 2400), 500, 250)
        assertThat(point).isEqualTo(MarkPoint(540, 600))
    }

    @Test
    fun markPointOfClampsOutOfRangeCoordsIntoBitmap() {
        val point = markPointOf(PixelSize(1080, 2400), 1000, 1200)
        assertThat(point).isEqualTo(MarkPoint(1079, 2399))
    }

    @Test
    fun markPointOfReturnsNullWhenEitherCoordMissing() {
        assertThat(markPointOf(PixelSize(1080, 2400), null, 10)).isNull()
        assertThat(markPointOf(PixelSize(1080, 2400), 10, null)).isNull()
    }

    @Test
    fun markPointOfOffsetsIntoContentArea() {
        // N204：内容区 (0,120)–(1080,2320)，归一化落点相对可见区换算。
        val point = markPointOf(PixelSize(1080, 2400), 500, 250, Rect(0, 120, 1080, 2320))
        assertThat(point).isEqualTo(MarkPoint(540, 670))
    }

    @Test
    fun projectPointOfShiftsByCropOffsetThenScales() {
        val projected =
            projectPointOf(
                MarkPoint(200, 400),
                shiftX = 100,
                shiftY = 300,
                scaleX = 0.5f,
                scaleY = 0.5f,
            )
        assertThat(projected).isEqualTo(MarkPoint(50, 50))
    }

    @Test
    fun projectPointOfWithoutCropKeepsScaledPosition() {
        val projected = projectPointOf(MarkPoint(300, 900), 0, 0, 0.5f, 0.5f)
        assertThat(projected).isEqualTo(MarkPoint(150, 450))
    }
}
