/**
 * 坐标空间换算、范围校验与视觉块缩图（docs/tech/05 §11、§12，R1/A5）。
 */
package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.llm.CoordSpace
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoordSpaceTest {
    private val screen1080x2400 = PixelSize(1080, 2400)

    private fun normalized(): CoordMapper =
        CoordMapper(CoordSpace.NORMALIZED_1000, screen1080x2400, seen = PixelSize(1080, 2400))

    private fun imagePixels(): CoordMapper =
        CoordMapper(CoordSpace.IMAGE_PIXELS, screen1080x2400, seen = PixelSize(540, 1200))

    @Test
    fun `normalized coordinates scale to screen pixels`() {
        val mapper = normalized()
        assertThat(mapper.toScreen(0, 0)).isEqualTo(Point(0, 0))
        assertThat(mapper.toScreen(500, 500)).isEqualTo(Point(540, 1200))
        assertThat(mapper.toScreen(333, 667)).isEqualTo(Point(360, 1601))
        // N204 审查：边界值按公式落到基准区下一格，钳到区内最后一像素（所见即所点）。
        assertThat(mapper.toScreen(1000, 1000)).isEqualTo(Point(1079, 2399))
    }

    @Test
    fun `normalized coordinates outside 0 to 1000 are rejected`() {
        val mapper = normalized()
        assertThat(mapper.toScreen(1001, 500)).isNull()
        assertThat(mapper.toScreen(-1, 500)).isNull()
        assertThat(mapper.toScreen(500, 1001)).isNull()
    }

    @Test
    fun `image pixels scale back by screen over seen ratio`() {
        val mapper = imagePixels()
        assertThat(mapper.toScreen(270, 600)).isEqualTo(Point(540, 1200))
        assertThat(mapper.toScreen(1, 1)).isEqualTo(Point(2, 2))
        assertThat(mapper.toScreen(539, 1199)).isEqualTo(Point(1078, 2398))
    }

    @Test
    fun `image pixels at the seen size edge are rejected`() {
        val mapper = imagePixels()
        assertThat(mapper.toScreen(540, 1199)).isNull()
        assertThat(mapper.toScreen(539, 1200)).isNull()
        assertThat(mapper.toScreen(-1, 0)).isNull()
    }

    @Test
    fun `normalized coordinates offset into content area`() {
        // N204：内容区 1080×2200 位于 (0,120)，坐标基准换算到可见区。
        val mapper =
            CoordMapper(
                CoordSpace.NORMALIZED_1000,
                screen1080x2400,
                seen = PixelSize(1080, 2400),
                area = Rect(0, 120, 1080, 2320),
            )
        assertThat(mapper.toScreen(0, 0)).isEqualTo(Point(0, 120))
        assertThat(mapper.toScreen(500, 500)).isEqualTo(Point(540, 1220))
        // 右/下沿钳到内容区内最后一格，y=1000 不落进导航条带。
        assertThat(mapper.toScreen(1000, 1000)).isEqualTo(Point(1079, 2319))
    }

    @Test
    fun `image pixels offset into content area`() {
        val mapper =
            CoordMapper(
                CoordSpace.IMAGE_PIXELS,
                screen1080x2400,
                seen = PixelSize(540, 1100),
                area = Rect(0, 120, 1080, 2320),
            )
        assertThat(mapper.toScreen(0, 0)).isEqualTo(Point(0, 120))
        assertThat(mapper.toScreen(539, 1099)).isEqualTo(Point(1078, 2318))
    }

    @Test
    fun `vision sizing keeps images within limits untouched`() {
        val size = VisionSizing.targetSize(720, 1280, BLOCK_SIZE, MAX_BLOCKS, MAX_LONG_EDGE)
        assertThat(size).isEqualTo(PixelSize(720, 1280))
    }

    @Test
    fun `vision sizing clamps the long edge proportionally`() {
        val size = VisionSizing.targetSize(2000, 400, BLOCK_SIZE, MAX_BLOCKS, MAX_LONG_EDGE)
        assertThat(size).isEqualTo(PixelSize(1568, 313))
    }

    @Test
    fun `vision sizing shrinks further when block count is over budget`() {
        val size = VisionSizing.targetSize(1400, 1400, BLOCK_SIZE, MAX_BLOCKS, MAX_LONG_EDGE)
        assertThat(VisionSizing.blockCount(size.width, size.height, BLOCK_SIZE)).isAtMost(MAX_BLOCKS)
        assertThat(size.width).isEqualTo(size.height)
        assertThat(size.width).isLessThan(1400)
    }

    private companion object {
        const val BLOCK_SIZE = 28
        const val MAX_BLOCKS = 1500
        const val MAX_LONG_EDGE = 1568
    }
}
