package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** DB 文字检测前后处理（ADR-0026）：输入几何/归一化、概率图 → 行框。 */
internal class TextDetCodecTest {
    private fun image(
        w: Int,
        h: Int,
        argb: Int = 0xFFFFFFFF.toInt(),
    ) = VisionImage(w, h, IntArray(w * h) { argb })

    /** 概率图：[rects] 内（含端点）填 [value]，其余 0。 */
    private fun probMap(
        w: Int,
        h: Int,
        vararg rects: Rect,
        value: Float = 0.9f,
    ): FloatArray {
        val prob = FloatArray(w * h)
        for (r in rects) {
            for (y in r.t..r.b) for (x in r.l..r.r) prob[y * w + x] = value
        }
        return prob
    }

    private fun inputFor(
        w: Int,
        h: Int,
        region: Rect = Rect(0, 0, w, h),
    ) = checkNotNull(TextDetCodec.input(image(w, h), region))

    @Test
    fun `input sides align to 32 and small crops keep native scale`() {
        val input = inputFor(200, 120, Rect(10, 10, 110, 60)) // 100×50 → round(3.125)=3 → 96，round(1.56)=2 → 64
        assertThat(input.width).isEqualTo(96)
        assertThat(input.height).isEqualTo(64)
        assertThat(input.chw).hasLength(3 * 96 * 64)
    }

    @Test
    fun `input shrinks only when the long side exceeds the limit and rounds half to even`() {
        val input = inputFor(1920, 480) // ratio 0.5 → 960×240；240/32=7.5 取偶 → 8×32=256
        assertThat(input.width).isEqualTo(960)
        assertThat(input.height).isEqualTo(256)
    }

    @Test
    fun `tiny crop is padded up to one alignment unit`() {
        val input = inputFor(40, 40, Rect(0, 0, 8, 8))
        assertThat(input.width).isEqualTo(TextDetCodec.ALIGN)
        assertThat(input.height).isEqualTo(TextDetCodec.ALIGN)
    }

    @Test
    fun `empty or out of image region gives no input`() {
        assertThat(TextDetCodec.input(image(50, 50), Rect(60, 60, 90, 90))).isNull()
        assertThat(TextDetCodec.input(image(50, 50), Rect(10, 10, 10, 30))).isNull()
    }

    @Test
    fun `input channels are BGR with imagenet normalization`() {
        val red = checkNotNull(TextDetCodec.input(image(32, 32, 0xFFFF0000.toInt()), Rect(0, 0, 32, 32)))
        val plane = red.width * red.height
        assertThat(red.chw[0]).isWithin(EPS).of((0f - 0.485f) / 0.229f) // B 通道在前
        assertThat(red.chw[plane]).isWithin(EPS).of((0f - 0.456f) / 0.224f) // G
        assertThat(red.chw[2 * plane]).isWithin(EPS).of((1f - 0.406f) / 0.225f) // R
    }

    @Test
    fun `boxes expands a text component by the unclip distance and maps back to the image`() {
        val input = inputFor(64, 64)
        // 横跨 x 10..40、y 20..30 的文字块：跨度 30×10，外扩距离 = 30*10*1.5/(2*40) = 5.625。
        val boxes = TextDetCodec.boxes(probMap(64, 64, Rect(10, 20, 40, 30)), 64, 64, input)
        assertThat(boxes).hasSize(1)
        assertThat(boxes.single().bounds).isEqualTo(Rect(4, 14, 46, 36))
        assertThat(boxes.single().score).isWithin(EPS.toDouble()).of(0.9)
    }

    @Test
    fun `boxes maps probability coordinates through the region offset and scale`() {
        val img = image(400, 400)
        val region = Rect(100, 200, 164, 264) // 64×64 → 输入 64×64，缩放 1:1
        val input = checkNotNull(TextDetCodec.input(img, region))
        val boxes = TextDetCodec.boxes(probMap(64, 64, Rect(10, 20, 40, 30)), 64, 64, input)
        assertThat(boxes.single().bounds).isEqualTo(Rect(104, 214, 146, 236))
    }

    @Test
    fun `components below box threshold or too thin are dropped`() {
        val input = inputFor(64, 64)
        val weak = TextDetCodec.boxes(probMap(64, 64, Rect(10, 20, 40, 30), value = 0.5f), 64, 64, input)
        val thin = TextDetCodec.boxes(probMap(64, 64, Rect(10, 20, 40, 21)), 64, 64, input)
        assertThat(weak).isEmpty()
        assertThat(thin).isEmpty()
    }

    @Test
    fun `diagonal touching pixels form one component and results are in reading order`() {
        val input = inputFor(96, 96)
        val prob = probMap(96, 96, Rect(10, 60, 40, 70), Rect(10, 10, 40, 20))
        // 另加一个与上块只在对角相接的块：8 连通应并入同一连通域（外接框变大，仍是一个框）。
        for (i in 0..3) prob[(21 + i) * 96 + 41 + i] = 0.9f
        val boxes = TextDetCodec.boxes(prob, 96, 96, input)
        assertThat(boxes).hasSize(2)
        assertThat(boxes[0].bounds.centerY).isLessThan(boxes[1].bounds.centerY)
    }

    @Test
    fun `invalid probability map yields no boxes`() {
        val input = inputFor(32, 32)
        assertThat(TextDetCodec.boxes(FloatArray(4), 32, 32, input)).isEmpty()
        assertThat(TextDetCodec.boxes(FloatArray(0), 0, 0, input)).isEmpty()
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
