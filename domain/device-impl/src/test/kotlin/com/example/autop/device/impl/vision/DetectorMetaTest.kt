package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ONNX metadata 档位裁决（vp8 §8.1/§8.2，ADR-0008 决策 2）：names 映射、labels_version 拒绝加载。 */
internal class DetectorMetaTest {
    @Test
    fun `v2 metadata maps names in id order with deploy conf`() {
        val tier =
            detectorTierOf(
                mapOf(
                    "names" to """["text", "icon", "button"]""",
                    "labels_version" to "2",
                    "deploy_conf" to "0.26",
                ),
            )
        val meta = (tier as DetectorTier.Multi).meta
        assertThat(meta.classNames).containsExactly("text", "icon", "button").inOrder()
        assertThat(meta.deployConf).isWithin(1e-9).of(0.26)
    }

    @Test
    fun `missing deploy conf falls back to frozen value`() {
        val tier = detectorTierOf(mapOf("names" to """["text"]""", "labels_version" to "2"))
        val meta = (tier as DetectorTier.Multi).meta
        assertThat(meta.deployConf).isWithin(1e-9).of(DetectorMeta.DEFAULT_DEPLOY_CONF)
    }

    @Test
    fun `missing labels version is single class tier`() {
        assertThat(detectorTierOf(emptyMap())).isEqualTo(DetectorTier.Single)
        assertThat(detectorTierOf(mapOf("names" to """["text"]"""))).isEqualTo(DetectorTier.Single)
    }

    @Test
    fun `unknown labels version is rejected`() {
        assertThat(detectorTierOf(mapOf("labels_version" to "3"))).isEqualTo(DetectorTier.Reject)
        assertThat(detectorTierOf(mapOf("labels_version" to "1"))).isEqualTo(DetectorTier.Reject)
    }

    @Test
    fun `v2 without names is rejected`() {
        assertThat(detectorTierOf(mapOf("labels_version" to "2"))).isEqualTo(DetectorTier.Reject)
        assertThat(detectorTierOf(mapOf("labels_version" to "2", "names" to "[]"))).isEqualTo(DetectorTier.Reject)
    }

    @Test
    fun `real export metadata parses as v2 multi class`() {
        // 与 poc/visual-parsing/out/vp8_export/gui_detect.onnx 实际 metadata 一致（P10 校验过）。
        val names =
            listOf(
                "text",
                "icon",
                "image",
                "button",
                "item",
                "input",
                "checkbox",
                "radio",
                "switch",
                "slider",
                "progress",
            )
        val raw = names.joinToString(", ", "[", "]") { "\"$it\"" }
        val tier = detectorTierOf(mapOf("names" to raw, "labels_version" to "2", "deploy_conf" to "0.26"))
        val meta = (tier as DetectorTier.Multi).meta
        assertThat(meta.classNames).hasSize(11)
        assertThat(meta.classNames.last()).isEqualTo("progress")
    }
}
