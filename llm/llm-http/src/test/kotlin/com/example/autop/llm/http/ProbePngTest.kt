package com.example.autop.llm.http

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** [ProbePng] 单测：输出必须是标准可解码的 8×8 PNG（DefaultModelProbe 的内置探测图）。 */
internal class ProbePngTest {
    @Test
    fun solidRgb8x8StartsWithPngSignature() {
        val bytes = ProbePng.solidRgb8x8()
        assertThat(bytes.copyOf(pngSignature().size).toList()).isEqualTo(pngSignature().toList())
    }

    @Test
    fun solidRgb8x8DecodesToEightByEight() {
        val image = ImageIO.read(ByteArrayInputStream(ProbePng.solidRgb8x8()))
        assertThat(image).isNotNull()
        assertThat(image.width).isEqualTo(ProbePng.SIZE)
        assertThat(image.height).isEqualTo(ProbePng.SIZE)
    }
}
