package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** [imageFileOf] 解析（N178：相对 ref 必须落到 filesDir，此前按 cwd 解码必失败）。 */
class BitmapImageLoaderTest {
    private val filesDir = File("files")

    @Test
    fun `relative ref resolves under filesDir instead of cwd`() {
        val ref = "threads/t1/img/img_1-1.jpg"

        val resolved = imageFileOf(filesDir, ref)

        assertThat(resolved).isEqualTo(File(filesDir, ref))
        assertThat(resolved).isNotEqualTo(File(ref))
    }

    @Test
    fun `absolute ref is used as-is`() {
        val absolute = File("img.jpg").absoluteFile

        assertThat(imageFileOf(filesDir, absolute.path)).isEqualTo(absolute)
    }
}
