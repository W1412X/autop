package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

/** 模型资产校验守卫（docs/design/visual-screen-parsing §9）：manifest SHA-256 语义与字表解码。 */
internal class VisionModelStoreTest {
    @Test
    fun `bytes pass through when manifest absent`() {
        val store = storeOf("gui_detect.onnx" to DATA)
        assertThat(store.bytes("gui_detect.onnx")).isEqualTo(DATA)
    }

    @Test
    fun `bytes verified when manifest hash matches`() {
        val store =
            storeOf(
                "gui_detect.onnx" to DATA,
                MANIFEST to manifest("gui_detect.onnx" to sha256(DATA)),
            )
        assertThat(store.bytes("gui_detect.onnx")).isEqualTo(DATA)
    }

    @Test
    fun `bytes rejected when manifest hash mismatches`() {
        val store =
            storeOf(
                "gui_detect.onnx" to DATA,
                MANIFEST to manifest("gui_detect.onnx" to sha256(OTHER)),
            )
        assertThat(store.bytes("gui_detect.onnx")).isNull()
    }

    @Test
    fun `bytes allowed when name not listed in manifest`() {
        val store =
            storeOf(
                "ocr_det.onnx" to DATA,
                MANIFEST to manifest("gui_detect.onnx" to sha256(DATA)),
            )
        assertThat(store.bytes("ocr_det.onnx")).isEqualTo(DATA)
    }

    @Test
    fun `unparseable manifest degrades to pass through`() {
        val store =
            storeOf(
                "gui_detect.onnx" to DATA,
                MANIFEST to "not a manifest".toByteArray(),
            )
        assertThat(store.bytes("gui_detect.onnx")).isEqualTo(DATA)
    }

    @Test
    fun `text decodes utf8 charset lines with trailing space class`() {
        val store = storeOf("ppocr_keys.txt" to "字\n符\n \n".toByteArray())
        val lines = store.text("ppocr_keys.txt")?.lines()?.dropLastWhile { it.isEmpty() }
        assertThat(lines).containsExactly("字", "符", " ").inOrder()
    }

    private fun storeOf(vararg entries: Pair<String, ByteArray>): VisionModelStore {
        val assets = entries.toMap()
        return VisionModelStore { name -> assets[name] }
    }

    private fun manifest(vararg entries: Pair<String, String>): ByteArray =
        entries
            .joinToString(prefix = "{\n", postfix = "\n}\n") { (name, hash) -> """  "$name": "$hash"""" }
            .toByteArray()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST = "manifest.json"
        val DATA = byteArrayOf(1, 2, 3)
        val OTHER = byteArrayOf(9, 9, 9)
    }
}
