package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 仓库里真实的视觉模型资产与 manifest.json 的一致性守卫。
 *
 * 运行时 [VisionModelStore] 对校验失败的资产静默返回 null，应用不报错、只是视觉模式悄悄失效，
 * 所以必须在构建期拦住：资产缺失、漏列、或哈希不一致（典型原因：文本资产在 Windows 与 Linux CI
 * 上换行不同，见仓库根的 `.gitattributes`）都会让这里失败。
 */
internal class VisionAssetsManifestTest {
    private val dir: File =
        File(requireNotNull(System.getProperty("autop.visionAssetDir")) { "autop.visionAssetDir 未设置" })

    @Test
    fun `every shipped asset is listed in the manifest and its hash matches`() {
        val manifest = dir.resolve("manifest.json").readText(Charsets.UTF_8)
        val expected =
            Regex("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                .findAll(manifest)
                .associate { it.groupValues[1] to it.groupValues[2] }
        assertThat(expected.keys).containsExactlyElementsIn(SHIPPED_ASSETS)
        for (name in SHIPPED_ASSETS) {
            val file = dir.resolve(name)
            assertThat(file.isFile).isTrue()
            assertThat(file.length()).isGreaterThan(0L)
            assertThat(sha256(file.readBytes())).isEqualTo(expected.getValue(name).lowercase())
        }
    }

    @Test
    fun `text assets use LF so every checkout has identical bytes`() {
        for (name in listOf("ppocr_keys.txt", "ocr_lexicon.txt", "manifest.json")) {
            assertThat(dir.resolve(name).readBytes().contains('\r'.code.toByte())).isFalse()
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        /** App 运行时会加载的全部视觉资产（与 VisionModelStore 的调用方一致）。 */
        val SHIPPED_ASSETS =
            listOf(
                "gui_detect.onnx",
                "state_cls.onnx",
                "icon_cls.onnx",
                "ocr_det.onnx",
                "ocr_rec.onnx",
                "ocr_rec_server.onnx",
                "ppocr_keys.txt",
                "ocr_lexicon.txt",
            )
    }
}
